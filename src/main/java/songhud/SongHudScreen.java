package songhud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** In-game menu: pick a song, control playback, switch HUD / AIR / FOLLOW, tune sync and size. */
public class SongHudScreen extends Screen {
    private TextFieldWidget field;
    private ButtonWidget modeBtn;
    private ButtonWidget pauseBtn;

    public SongHudScreen() { super(Text.literal("SongHUD")); }

    private ButtonWidget btn(String label, int x, int y, int w, Runnable r) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> r.run())
                .dimensions(x, y, w, 24).build());
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = Math.max(20, height / 2 - 135);

        field = new TextFieldWidget(textRenderer, cx - 150, y, 300, 22, Text.literal("Song"));
        field.setMaxLength(150);
        field.setText(SongHud.lastQuery);
        field.setPlaceholder(Text.literal("artist - title"));
        addDrawableChild(field);
        setInitialFocus(field);
        y += 28;

        btn("Search / Play", cx - 150, y, 300, () -> {
            SongHud.lastQuery = field.getText();
            SongHud.saveCfg();
            SongHud.search(field.getText(), msg -> {});
        });
        y += 28;

        pauseBtn = btn(SongHud.running ? "Pause" : "Resume", cx - 150, y, 148, () -> {
            if (SongHud.running) { SongHud.pause(); SongHud.status = "Paused"; pauseBtn.setMessage(Text.literal("Resume")); }
            else { SongHud.resume(); SongHud.status = "Playing"; pauseBtn.setMessage(Text.literal("Pause")); }
        });
        btn("Stop", cx + 2, y, 148, SongHud::stop);
        y += 28;

        modeBtn = btn("Mode: " + SongHud.mode, cx - 150, y, 300, () -> {
            SongHud.mode = SongHud.mode.next();
            SongHud.anchored = false;
            SongHud.saveCfg();
            modeBtn.setMessage(Text.literal("Mode: " + SongHud.mode));
        });
        y += 28;

        btn("Sync -100ms", cx - 150, y, 148, () -> SongHud.offsetMs -= 100);
        btn("Sync +100ms", cx + 2, y, 148, () -> SongHud.offsetMs += 100);
        y += 28;

        btn("Text -", cx - 150, y, 98, () -> { SongHud.scale = Math.max(0.3, SongHud.scale - 0.1); SongHud.saveCfg(); });
        btn("Text +", cx - 50, y, 98, () -> { SongHud.scale = Math.min(4.0, SongHud.scale + 0.1); SongHud.saveCfg(); });
        btn("Here", cx + 52, y, 98, () -> SongHud.anchored = false);
        y += 28;

        btn("Closer", cx - 150, y, 148, () -> { SongHud.dist = Math.max(1.5, SongHud.dist - 0.5); SongHud.anchored = false; SongHud.saveCfg(); });
        btn("Farther", cx + 2, y, 148, () -> { SongHud.dist = Math.min(12.0, SongHud.dist + 0.5); SongHud.anchored = false; SongHud.saveCfg(); });
        y += 28;

        btn("Spotify auto-sync...", cx - 150, y, 300,
                () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(new SpotifyScreen(this)));
        y += 28;

        btn("Done", cx - 150, y, 300, this::close);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        int top = Math.max(20, height / 2 - 135);
        String info = String.format("%s | sync %d ms | text x%.1f | dist %.1f",
                SongHud.status, SongHud.offsetMs, SongHud.scale, SongHud.dist);
        ctx.drawTextWithShadow(textRenderer, info, cx - textRenderer.getWidth(info) / 2, top - 14, 0xFFCCCCCC);
    }

    @Override
    public boolean shouldPause() { return false; }
}
