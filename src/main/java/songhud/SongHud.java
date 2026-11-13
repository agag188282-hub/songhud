package songhud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SongHUD: original, pure-Java synced lyrics overlay (HUD or floating "in the air").
 * No native code, so it works in Android launchers. Lyrics come from the public LRCLIB API.
 */
public class SongHud implements ClientModInitializer {
    record Line(long ms, String text) {}

    enum Mode {
        HUD, AIR, FOLLOW;
        Mode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Pattern LRC = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)](.*)");

    static volatile List<Line> lines = List.of();
    static volatile String title = "";
    static volatile String status = "Idle";
    static volatile boolean running = false;
    static volatile long baseNanos = 0;
    static volatile long baseMs = 0;
    static volatile long offsetMs = 0;
    static volatile double scale = 1.0;
    static volatile double dist = 4.0;
    static volatile Mode mode = Mode.FOLLOW;
    static volatile String lastQuery = "";

    // world anchor for AIR / FOLLOW modes
    static double ax, ay, az;
    static boolean anchored = false;
    static long lastNanos = 0;

    // ---------- clock ----------
    static long position() {
        long p = running ? baseMs + (System.nanoTime() - baseNanos) / 1_000_000L : baseMs;
        return p + offsetMs;
    }
    static void setPosition(long ms) { baseMs = ms; baseNanos = System.nanoTime(); }
    static void pause()  { setPosition(position() - offsetMs); running = false; }
    static void resume() { setPosition(baseMs); running = true; }
    static void stop()   { running = false; lines = List.of(); title = ""; status = "Stopped"; }

    // ---------- config ----------
    static Path cfgPath() { return FabricLoader.getInstance().getConfigDir().resolve("songhud.json"); }

    static void loadCfg() {
        try {
            Path p = cfgPath();
            if (!Files.exists(p)) return;
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (o.has("mode")) mode = Mode.valueOf(o.get("mode").getAsString());
            if (o.has("scale")) scale = o.get("scale").getAsDouble();
            if (o.has("dist")) dist = o.get("dist").getAsDouble();
            if (o.has("lastQuery")) lastQuery = o.get("lastQuery").getAsString();
        } catch (Exception ignored) {}
    }

    static void saveCfg() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("mode", mode.name());
            o.addProperty("scale", scale);
            o.addProperty("dist", dist);
            o.addProperty("lastQuery", lastQuery);
            Files.writeString(cfgPath(), o.toString());
        } catch (Exception ignored) {}
    }

    // ---------- init / commands ----------
    @Override
    public void onInitializeClient() {
        loadCfg();
        SpotifySync.load();

        // Keybind (Right Shift by default). Can be mapped to an on-screen button in Zalith controls.
        KeyBinding menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.songhud.menu", InputUtil.Type.KEYSYM, 344,
                KeyBinding.Category.create(Identifier.of("songhud", "main"))));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new SongHudScreen());
            }
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("songhud", "lyrics"), (ctx, tick) -> render(ctx));

        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(
            ClientCommandManager.literal("lyrics")
                .then(ClientCommandManager.literal("menu").executes(c -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    mc.send(() -> mc.setScreen(new SongHudScreen()));
                    return 1; }))
                .then(ClientCommandManager.literal("play")
                    .then(ClientCommandManager.argument("song", StringArgumentType.greedyString())
                        .executes(c -> { lastQuery = StringArgumentType.getString(c, "song");
                            search(lastQuery, feedback(c.getSource())); saveCfg(); return 1; })))
                .then(ClientCommandManager.literal("spotify")
                    .then(ClientCommandManager.literal("on").executes(c -> { SpotifySync.setEnabled(true); return 1; }))
                    .then(ClientCommandManager.literal("off").executes(c -> { SpotifySync.setEnabled(false); return 1; })))
                .then(ClientCommandManager.literal("pause").executes(c -> { pause(); status = "Paused"; return 1; }))
                .then(ClientCommandManager.literal("resume").executes(c -> { resume(); status = "Playing"; return 1; }))
                .then(ClientCommandManager.literal("stop").executes(c -> { stop(); return 1; }))
                .then(ClientCommandManager.literal("here").executes(c -> { anchored = false; return 1; }))
                .then(ClientCommandManager.literal("mode")
                    .then(ClientCommandManager.literal("hud").executes(c -> { mode = Mode.HUD; saveCfg(); return 1; }))
                    .then(ClientCommandManager.literal("air").executes(c -> { mode = Mode.AIR; anchored = false; saveCfg(); return 1; }))
                    .then(ClientCommandManager.literal("follow").executes(c -> { mode = Mode.FOLLOW; saveCfg(); return 1; })))
                .then(ClientCommandManager.literal("seek")
                    .then(ClientCommandManager.argument("sec", IntegerArgumentType.integer(0)).executes(c -> {
                        setPosition(IntegerArgumentType.getInteger(c, "sec") * 1000L - offsetMs); return 1; })))
                .then(ClientCommandManager.literal("offset")
                    .then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(-30000, 30000)).executes(c -> {
                        offsetMs = IntegerArgumentType.getInteger(c, "ms"); return 1; })))
                .then(ClientCommandManager.literal("scale")
                    .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg(0.3, 4.0)).executes(c -> {
                        scale = DoubleArgumentType.getDouble(c, "x"); saveCfg(); return 1; })))
                .then(ClientCommandManager.literal("dist")
                    .then(ClientCommandManager.argument("blocks", DoubleArgumentType.doubleArg(1.5, 12.0)).executes(c -> {
                        dist = DoubleArgumentType.getDouble(c, "blocks"); anchored = false; saveCfg(); return 1; })))
        ));
    }

    static Consumer<String> feedback(FabricClientCommandSource s) {
        return msg -> MinecraftClient.getInstance().execute(() -> s.sendFeedback(Text.literal("[SongHUD] " + msg)));
    }

    // ---------- lyrics search ----------
    static void search(String query, Consumer<String> say) {
        if (SpotifySync.enabled) { status = "Spotify auto-sync is ON (turn it off for manual search)"; return; }
        if (query == null || query.isBlank()) { status = "Enter a song"; return; }
        status = "Searching...";
        say.accept("Searching: " + query);
        Thread t = new Thread(() -> {
            try {
                String url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "SongHUD/1.1").GET().build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    if (o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull()) {
                        List<Line> parsed = parse(o.get("syncedLyrics").getAsString());
                        if (parsed.isEmpty()) continue;
                        String name = o.get("artistName").getAsString() + " - " + o.get("trackName").getAsString();
                        MinecraftClient.getInstance().execute(() -> {
                            lines = parsed; title = name; offsetMs = 0;
                            setPosition(0); running = true; anchored = false;
                            status = "Playing: " + name;
                            say.accept("Playing: " + name + " (start your music now)");
                        });
                        return;
                    }
                }
                status = "No synced lyrics found";
                say.accept(status);
            } catch (Exception ex) {
                status = "Error: " + ex.getMessage();
                say.accept(status);
            }
        }, "songhud-fetch");
        t.setDaemon(true);
        t.start();
    }

    static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    static HttpResponse<String> httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "SongHUD/1.2").GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Synced lyrics for an exact track (used by Spotify mode). Returns null if none found. */
    static List<Line> fetchLyrics(String artist, String track, int durSec) throws Exception {
        String q = "artist_name=" + enc(artist) + "&track_name=" + enc(track) + (durSec > 0 ? "&duration=" + durSec : "");
        HttpResponse<String> r = httpGet("https://lrclib.net/api/get?" + q);
        if (r.statusCode() == 200) {
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            if (o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull()) {
                List<Line> l = parse(o.get("syncedLyrics").getAsString());
                if (!l.isEmpty()) return l;
            }
        }
        r = httpGet("https://lrclib.net/api/search?q=" + enc(artist + " " + track));
        JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull()) {
                List<Line> l = parse(o.get("syncedLyrics").getAsString());
                if (!l.isEmpty()) return l;
            }
        }
        return null;
    }

    static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = LRC.matcher(raw.trim());
            if (!m.matches()) continue;
            long ms = (long) (Integer.parseInt(m.group(1)) * 60_000L + Double.parseDouble(m.group(2)) * 1000.0);
            out.add(new Line(ms, m.group(3).trim()));
        }
        return out;
    }

    // ---------- rendering ----------
    static void render(DrawContext ctx) {
        List<Line> ls = lines;
        if (ls.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;

        long pos = position();
        int cur = -1;
        for (int i = 0; i < ls.size(); i++) {
            if (ls.get(i).ms() <= pos) cur = i; else break;
        }
        if (mode == Mode.HUD) renderHud(ctx, mc, ls, cur);
        else renderAir(ctx, mc, ls, cur);
    }

    static String lineText(List<Line> ls, int idx) {
        if (idx < 0 || idx >= ls.size()) return null;
        String t = ls.get(idx).text();
        return t.isEmpty() ? "♪" : t;
    }

    static void renderHud(DrawContext ctx, MinecraftClient mc, List<Line> ls, int cur) {
        TextRenderer tr = mc.textRenderer;
        int w = ctx.getScaledWindowWidth();
        float s = (float) scale;
        int y = 8;
        drawCentered(ctx, tr, title, w / 2f, y, 0xFFAAAAAA, 0.8f * s);
        y += (int) (12 * s);
        for (int k = -1; k <= 1; k++) {
            String txt = lineText(ls, cur + k);
            boolean main = k == 0;
            if (txt != null)
                drawCentered(ctx, tr, txt, w / 2f, y, main ? 0xFFFFFFFF : 0x99FFFFFF, (main ? 1.3f : 0.9f) * s);
            y += (int) ((main ? 16 : 12) * s);
        }
    }

    static void renderAir(DrawContext ctx, MinecraftClient mc, List<Line> ls, int cur) {
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d c = cam.getPos();
        double yaw = Math.toRadians(cam.getYaw()), pit = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pit), fy = -Math.sin(pit), fz = Math.cos(yaw) * Math.cos(pit);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;

        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;

        double tx = c.x + fx * dist, ty = c.y + fy * dist, tz = c.z + fz * dist;
        if (!anchored) { ax = tx; ay = ty; az = tz; anchored = true; }
        else if (mode == Mode.FOLLOW) {
            double k = 1 - Math.exp(-dt * 3.0);
            ax += (tx - ax) * k; ay += (ty - ay) * k; az += (tz - az) * k;
        }

        TextRenderer tr = mc.textRenderer;
        int W = ctx.getScaledWindowWidth(), H = ctx.getScaledWindowHeight();
        double tanHalf = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
        double aspect = (double) W / H;

        // k: -2 = title, -1 = previous, 0 = current, 1 = next (stacked along camera-up)
        for (int k = -2; k <= 1; k++) {
            String txt = k == -2 ? title : lineText(ls, cur + k);
            if (txt == null || txt.isEmpty()) continue;
            double off = -k * 0.55 * scale;
            double px = ax + ux * off - c.x, py = ay + uy * off - c.y, pz = az + uz * off - c.z;
            double x = px * rx + pz * rz;                // dot right (ry = 0)
            double y = px * ux + py * uy + pz * uz;      // dot up
            double z = px * fx + py * fy + pz * fz;      // dot forward
            if (z < 0.3) continue;                       // behind camera
            double ppb = (H / 2.0) / (z * tanHalf);      // gui pixels per block at this depth
            float sx = (float) (W / 2.0 + x / (z * tanHalf * aspect) * (W / 2.0));
            float sy = (float) (H / 2.0 - y / (z * tanHalf) * (H / 2.0));
            boolean main = k == 0;
            float base = k == -2 ? 0.025f : (main ? 0.05f : 0.035f);
            float sc = (float) Math.max(0.15, Math.min(6.0, ppb * base * scale));
            int color = k == -2 ? 0xFFAAAAAA : (main ? 0xFFFFFFFF : 0x99FFFFFF);
            drawCentered(ctx, tr, txt, sx, sy, color, sc);
        }
    }

    static void drawCentered(DrawContext ctx, TextRenderer tr, String text, float x, float y, int color, float sc) {
        if (text == null || text.isEmpty()) return;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(sc, sc);
        ctx.drawTextWithShadow(tr, text, -tr.getWidth(text) / 2, -4, color);
        ctx.getMatrices().popMatrix();
    }
}
