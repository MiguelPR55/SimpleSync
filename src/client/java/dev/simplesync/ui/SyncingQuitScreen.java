package dev.simplesync.ui;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.StatusSnapshot;
import dev.simplesync.sync.SyncStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen displayed when the user attempts to quit Minecraft while a world sync/upload is active.
 * Displays live progress and allows waiting for auto-close or canceling the sync to exit immediately.
 */
public class SyncingQuitScreen extends Screen {

    private Button waitButton;
    private Button cancelQuitButton;
    private boolean isWaitingMode = true;

    public SyncingQuitScreen() {
        super(Component.literal("SimpleSync - Uploading World"));
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int buttonWidth = 240;
        int buttonHeight = 20;
        int startY = this.height / 2 + 20;

        // Button 1: Wait & Close Automatically
        this.waitButton = Button.builder(
                Component.literal("Wait for Sync & Close Automatically"),
                button -> {
                    this.isWaitingMode = true;
                    button.active = false;
                    button.setMessage(Component.literal("Waiting for sync to complete..."));
                }
        ).bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight).build();
        // By default we are waiting automatically
        this.waitButton.active = false;
        this.waitButton.setMessage(Component.literal("Waiting for sync to complete..."));

        // Button 2: Cancel & Quit Immediately
        this.cancelQuitButton = Button.builder(
                Component.literal("Cancel Sync & Quit Immediately"),
                button -> {
                    CloudSyncManager.getInstance().cancelCurrentSync();
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) {
                        client.stop();
                    }
                }
        ).bounds(centerX - buttonWidth / 2, startY + 26, buttonWidth, buttonHeight).build();

        this.addRenderableWidget(this.waitButton);
        this.addRenderableWidget(this.cancelQuitButton);
    }

    @Override
    public void tick() {
        super.tick();
        if (isWaitingMode) {
            SyncStatus status = CloudSyncManager.getInstance().getStatus();
            if (!status.isBusy()) {
                Minecraft client = Minecraft.getInstance();
                if (client != null) {
                    client.stop();
                }
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        StatusSnapshot snapshot = CloudSyncManager.getInstance().getStatusSnapshot();
        SyncStatus status = snapshot.status();
        String detail = snapshot.detail() != null ? snapshot.detail() : "";

        String titleText = "Uploading world to Google Drive...";
        String statusText = Component.translatable(status.getTranslationKey()).getString();
        if (!detail.isEmpty()) {
            statusText += " (" + detail + ")";
        }

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        extractor.centeredText(this.font, Component.literal(titleText), centerX, centerY - 55, 0xFFFFFFFF);
        extractor.centeredText(this.font, Component.literal(statusText), centerX, centerY - 30, 0xFF4FC3F7);
        extractor.centeredText(this.font, Component.literal("Minecraft will close automatically once the sync reaches 100%."), centerX, centerY - 5, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
