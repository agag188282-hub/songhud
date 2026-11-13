package songhud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spotify auto-sync (Web API, PKCE login). Needs the user's own Client ID.
 * Reads the currently playing track + position every few seconds and keeps the lyrics clock in sync.
 */
public final class SpotifySync {
    static final String REDIRECT = "http://127.0.0.1:8789/callback";
    static final String SCOPE = "user-read-currently-playing user-read-playback-state";

    static volatile String clientId = "";
    static volatile String refreshToken = "";
    static volatile String accessToken = "";
    static volatile long tokenExpMs = 0;
    static volatile boolean enabled = false;
    static volatile String msg = "";

    private static String verifier = "";
    private static ServerSocket server;
    private static Thread poller;
    private static String trackId = "";

    private SpotifySync() {}

    // ---------- persistence ----------
    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("songhud_spotify.json"); }

    static void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (o.has("clientId")) clientId = o.get("clientId").getAsString();
            if (o.has("refreshToken")) refreshToken = o.get("refreshToken").getAsString();
            if (o.has("enabled")) enabled = o.get("enabled").getAsBoolean();
        } catch (Exception ignored) {}
        if (enabled && !refreshToken.isEmpty()) startPolling();
        else enabled = false;
    }

    static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("clientId", clientId);
            o.addProperty("refreshToken", refreshToken);
            o.addProperty("enabled", enabled);
            Files.writeString(file(), o.toString());
        } catch (Exception ignored) {}
    }

    static void setEnabled(boolean on) {
        if (on && refreshToken.isEmpty()) { msg = "Not logged in yet"; SongHud.status = msg; return; }
        enabled = on;
        save();
        if (on) startPolling(); else { SongHud.status = "Spotify sync off"; }
    }

    static void disconnect() {
        enabled = false; refreshToken = ""; accessToken = ""; trackId = "";
        save();
        msg = "Disconnected";
    }

    // ---------- login (PKCE) ----------
    static String buildAuthUrl() throws Exception {
        byte[] rnd = new byte[48];
        new SecureRandom().nextBytes(rnd);
        verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        return "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(REDIRECT)
                + "&scope=" + enc(SCOPE)
                + "&code_challenge_method=S256&code_challenge=" + enc(challenge);
    }

    /** Listens on 127.0.0.1:8789 for the redirect (works if the game stays running while you log in). */
    static void startCallbackServer() {
        closeServer();
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(8789, 1, InetAddress.getLoopbackAddress());
                server.setSoTimeout(300_000);
                try (Socket s = server.accept()) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String first = br.readLine();
                    String body = "<html><body style='font-family:sans-serif'><h2>SongHUD</h2>Logged in. You can go back to Minecraft.</body></html>";
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                            + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body)
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (first != null) finishLogin(first);
                }
            } catch (Exception ignored) {
            } finally { closeServer(); }
        }, "songhud-callback");
        t.setDaemon(true);
        t.start();
    }

    static void closeServer() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    /** Accepts the full redirected URL, a request line, or the bare code. */
    static void finishLogin(String pasted) {
        if (pasted == null || pasted.isBlank()) { msg = "Paste the link first"; return; }
        Matcher m = Pattern.compile("[?&]code=([^&\\s]+)").matcher(pasted);
        String code = m.find() ? m.group(1) : pasted.trim();
        Thread t = new Thread(() -> {
            try {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("client_id", clientId);
                f.put("grant_type", "authorization_code");
                f.put("code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8));
                f.put("redirect_uri", REDIRECT);
                f.put("code_verifier", verifier);
                JsonObject o = tokenRequest(f);
                accessToken = o.get("access_token").getAsString();
                tokenExpMs = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000L - 60_000L;
                if (o.has("refresh_token")) refreshToken = o.get("refresh_token").getAsString();
                enabled = true;
                save();
                msg = "Logged in! Auto-sync ON";
                SongHud.status = msg;
                startPolling();
            } catch (Exception e) {
                msg = "Login failed: " + e.getMessage();
                SongHud.status = msg;
            }
        }, "songhud-login");
        t.setDaemon(true);
        t.start();
    }

    // ---------- tokens ----------
    static JsonObject tokenRequest(Map<String, String> form) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (var e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build();
        HttpResponse<String> r = SongHud.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("token HTTP " + r.statusCode());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    static void ensureToken() throws Exception {
        if (!accessToken.isEmpty() && System.currentTimeMillis() < tokenExpMs) return;
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refreshToken);
        f.put("client_id", clientId);
        JsonObject o = tokenRequest(f);
        accessToken = o.get("access_token").getAsString();
        tokenExpMs = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000L - 60_000L;
        if (o.has("refresh_token")) { refreshToken = o.get("refresh_token").getAsString(); save(); }
    }

    // ---------- polling ----------
    static synchronized void startPolling() {
        if (poller != null && poller.isAlive()) return;
        poller = new Thread(SpotifySync::loop, "songhud-spotify");
        poller.setDaemon(true);
        poller.start();
    }

    private static void loop() {
        while (enabled) {
            long sleep = 2500;
            try {
                ensureToken();
                long t0 = System.nanoTime();
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                        .header("Authorization", "Bearer " + accessToken).GET().build();
                HttpResponse<String> r = SongHud.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                long rttMs = (System.nanoTime() - t0) / 1_000_000L;
                int code = r.statusCode();
                if (code == 204) {
                    SongHud.status = "Spotify: nothing playing";
                    if (SongHud.running) SongHud.pause();
                } else if (code == 401) {
                    accessToken = "";
                    sleep = 500;
                } else if (code == 429) {
                    sleep = r.headers().firstValue("Retry-After").map(v -> Long.parseLong(v) * 1000L).orElse(5000L);
                } else if (code == 200) {
                    handle(JsonParser.parseString(r.body()).getAsJsonObject(), rttMs);
                } else {
                    SongHud.status = "Spotify HTTP " + code;
                    sleep = 5000;
                }
            } catch (Exception e) {
                SongHud.status = "Spotify error: " + e.getMessage();
                sleep = 5000;
            }
            try { Thread.sleep(sleep); } catch (InterruptedException ignored) { return; }
        }
    }

    private static void handle(JsonObject o, long rttMs) {
        if (!o.has("item") || o.get("item").isJsonNull()) { SongHud.status = "Spotify: no track info"; return; }
        JsonObject item = o.getAsJsonObject("item");
        String id = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : item.get("name").getAsString();
        String name = item.get("name").getAsString();
        JsonArray artists = item.has("artists") ? item.getAsJsonArray("artists") : new JsonArray();
        String artist = artists.size() > 0 ? artists.get(0).getAsJsonObject().get("name").getAsString() : "";
        int durSec = item.has("duration_ms") ? (int) Math.round(item.get("duration_ms").getAsLong() / 1000.0) : 0;
        boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
        long progress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
        long now = progress + (playing ? rttMs / 2 : 0);

        if (!id.equals(trackId)) {
            trackId = id;
            SongHud.title = artist + " - " + name;
            SongHud.lines = List.of();
            SongHud.status = "Spotify: loading lyrics...";
            final String tid = id;
            try {
                List<SongHud.Line> l = SongHud.fetchLyrics(artist, name, durSec);
                if (!tid.equals(trackId)) return; // skipped to another song meanwhile
                if (l == null) { SongHud.status = "Spotify: no synced lyrics for " + SongHud.title; }
                else { SongHud.lines = l; SongHud.status = "Spotify: " + SongHud.title; SongHud.anchored = false; }
            } catch (Exception e) {
                SongHud.status = "Lyrics error: " + e.getMessage();
            }
            SongHud.setPosition(now);
            SongHud.running = playing;
            return;
        }

        long expected = SongHud.running
                ? SongHud.baseMs + (System.nanoTime() - SongHud.baseNanos) / 1_000_000L
                : SongHud.baseMs;
        if (playing != SongHud.running) { SongHud.setPosition(now); SongHud.running = playing; }
        else if (Math.abs(expected - now) > 450) SongHud.setPosition(now);
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static void copy(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.keyboard.setClipboard(text));
    }
}
