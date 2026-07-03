package dev.simplesync.ui;

import dev.simplesync.SimpleSync;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screen displayed when connecting SimpleSync to Google Drive via OAuth Device Authorization Grant.
 * Shows the authorization code and verification URL, allowing the user to open their browser or copy the code.
 */
public class DeviceAuthScreen extends Screen {

    private final Screen parent;
    private final String userCode;
    private final String verificationUrl;
    private final long expiresInSeconds;
    private final Runnable onCancel;
    private final AtomicBoolean resolved = new AtomicBoolean(false);

    public DeviceAuthScreen(Screen parent, String userCode, String verificationUrl, long expiresInSeconds, Runnable onCancel) {
        super(Text.translatable("simplesync.auth.title"));
        this.parent = parent;
        this.userCode = userCode;
        this.verificationUrl = verificationUrl;
        this.expiresInSeconds = expiresInSeconds;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonWidth = 140;
        int buttonY = this.height / 2 + 30;

        // Open Browser button
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("simplesync.auth.open_browser"),
                        button -> SimpleSync.openUrl(verificationUrl))
                .dimensions(centerX - buttonWidth - 5, buttonY, buttonWidth, 20)
                .build());

        // Copy Code button
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("simplesync.auth.copy_code"),
                        button -> {
                            if (MinecraftClient.getInstance().keyboard != null) {
                                MinecraftClient.getInstance().keyboard.setClipboard(userCode);
                            }
                        })
                .dimensions(centerX + 5, buttonY, buttonWidth, 20)
                .build());

        // Cancel button
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("simplesync.auth.cancel"),
                        button -> {
                            if (resolved.compareAndSet(false, true)) {
                                if (onCancel != null) {
                                    onCancel.run();
                                }
                                this.close();
                            }
                        })
                .dimensions(centerX - 100, buttonY + 30, 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int y = this.height / 2 - 65;

        // Title
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("simplesync.auth.title"), centerX, y, 0xFFFFFF);
        y += 22;

        // URL Instruction
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("simplesync.auth.url_instruction"), centerX, y, 0xDDDDDD);
        y += 15;

        // Verification URL (in cyan)
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(verificationUrl), centerX, y, 0x55FFFF);
        y += 22;

        // Code Instruction
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("simplesync.auth.code_instruction"), centerX, y, 0xDDDDDD);
        y += 16;

        // User Code (in yellow bold)
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§l" + userCode), centerX, y, 0xFFD54F);
        y += 22;

        // Expiration info
        long mins = Math.max(1, expiresInSeconds / 60);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("simplesync.auth.expires", mins), centerX, y, 0xAAAAAA);
    }

    @Override
    public void close() {
        if (resolved.compareAndSet(false, true) && onCancel != null) {
            onCancel.run();
        }
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
