package dev.simplesync.ui;

import dev.simplesync.SimpleSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

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
        super(Component.translatable("simplesync.auth.title"));
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
        int buttonY = this.height / 2 + 50;

        // Open Browser button
        this.addRenderableWidget(Button.builder(
                        Component.translatable("simplesync.auth.open_browser"),
                        button -> SyncConfigScreen.confirmAndOpenUrl(this, verificationUrl))
                .bounds(centerX - buttonWidth - 5, buttonY, buttonWidth, 20)
                .build());

        // Copy Code button
        this.addRenderableWidget(Button.builder(
                        Component.translatable("simplesync.auth.copy_code"),
                        button -> {
                            if (Minecraft.getInstance().keyboardHandler != null) {
                                Minecraft.getInstance().keyboardHandler.setClipboard(userCode);
                            }
                        })
                .bounds(centerX + 5, buttonY, buttonWidth, 20)
                .build());

        // Cancel button
        this.addRenderableWidget(Button.builder(
                        Component.translatable("simplesync.auth.cancel"),
                        button -> {
                            if (resolved.compareAndSet(false, true)) {
                                if (onCancel != null) {
                                    onCancel.run();
                                }
                                this.onClose();
                            }
                        })
                .bounds(centerX - 100, buttonY + 28, 200, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int y = this.height / 2 - 65;

        // Title
        extractor.centeredText(this.font,
                Component.translatable("simplesync.auth.title"), centerX, y, 0xFFFFFFFF);
        y += 22;

        // URL Instruction
        extractor.centeredText(this.font,
                Component.translatable("simplesync.auth.url_instruction"), centerX, y, 0xFFDDDDDD);
        y += 15;

        // Verification URL (in cyan)
        extractor.centeredText(this.font,
                Component.literal(verificationUrl), centerX, y, 0xFF55FFFF);
        y += 22;

        // Code Instruction
        extractor.centeredText(this.font,
                Component.translatable("simplesync.auth.code_instruction"), centerX, y, 0xFFDDDDDD);
        y += 16;

        // User Code (in yellow bold)
        extractor.centeredText(this.font,
                Component.literal("§l" + userCode), centerX, y, 0xFFFFD54F);
        y += 22;

        // Expiration info
        long mins = Math.max(1, expiresInSeconds / 60);
        extractor.centeredText(this.font,
                Component.translatable("simplesync.auth.expires", mins), centerX, y, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        if (resolved.compareAndSet(false, true) && onCancel != null) {
            onCancel.run();
        }
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }
}
