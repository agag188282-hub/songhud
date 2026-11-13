package songhud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Spotify login + auto-sync toggle. */
public class SpotifyScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget idField, pasteField;
    private ButtonWidget toggleBtn;

    public SpotifyScreen(Screen parent) { super(Text.literal("SongHUD - Spotify")); this.parent = parent; }

    private ButtonWidget btn(String label, int x, int y, int w, Runnable r) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> r.run()).dimensions(x, y, w, 24).build());
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = Math.max(24, height / 2 - 105);

        idField = new TextFieldWidget(textRenderer, cx - 150, y, 300, 22, Text.literal("Client ID"));
        idField.setMaxLength(64);
        idField.setText(SpotifySync.clientId);
        idField.setPlaceholder(Text.literal("Spotify Client ID"));
        addDrawableChild(idField);
        y += 28;

        btn("1. Connect (copies login link)", cx - 150, y, 300, () -> {
            String id = idField.getText().trim();
            if (id.isEmpty()) { SpotifySync.msg = "Enter Client ID first"; return; }
            SpotifySync.clientId = id;
            SpotifySync.save();
            try {
                String url = SpotifySync.buildAuthUrl();
                SpotifySync.copy(url);
                SpotifySync.startCallbackServer();
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.inGameHud.getChatHud().addMessage(Text.literal("[SongHUD] Open this link in your browser: " + url));
                SpotifySync.msg = "Link copied + sent to chat. Log in, then paste the final address below.";
            } catch (Exception e) { SpotifySync.msg = "Error: " + e.getMessage(); }
        });
        y += 28;

        pasteField = new TextFieldWidget(textRenderer, cx - 150, y, 300, 22, Text.literal("Paste"));
        pasteField.setMaxLength(2000);
        pasteField.setPlaceholder(Text.literal("2. Paste address after login (if needed)"));
        addDrawableChild(pasteField);
        y += 28;

        btn("Finish login", cx - 150, y, 300, () -> SpotifySync.finishLogin(pasteField.getText()));
        y += 28;

        toggleBtn = btn("Auto-sync: " + (SpotifySync.enabled ? "ON" : "OFF"), cx - 150, y, 300, () -> {
            SpotifySync.setEnabled(!SpotifySync.enabled);
            toggleBtn.setMessage(Text.literal("Auto-sync: " + (SpotifySync.enabled ? "ON" : "OFF")));
        });
        y += 28;

        btn("Disconnect", cx - 150, y, 148, () -> {
            SpotifySync.disconnect();
            toggleBtn.setMessage(Text.literal("Auto-sync: OFF"));
        });
        btn("Back", cx + 2, y, 148, this::close);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        int top = Math.max(24, height / 2 - 105);
        String a = SpotifySync.msg.isEmpty() ? "Redirect URI: " + SpotifySync.REDIRECT : SpotifySync.msg;
        ctx.drawTextWithShadow(textRenderer, a, cx - textRenderer.getWidth(a) / 2, top - 14, 0xFFCCCCCC);
        String b = SongHud.status;
        ctx.drawTextWithShadow(textRenderer, b, cx - textRenderer.getWidth(b) / 2, height - 16, 0xFF99FF99);
    }

    @Override
    public void close() { MinecraftClient.getInstance().setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
