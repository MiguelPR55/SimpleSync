package dev.simplesync.ui;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.StatusSnapshot;
import dev.simplesync.sync.SyncStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Screen displayed when the user attempts to quit Minecraft while a world sync/upload is active.
 * Displays live progress and allows waiting or canceling the sync to exit immediately.
 */
public class SyncingQuitScreen extends Screen {

    private Button cancelQuitButton;

    public SyncingQuitScreen() {
        super(Component.literal("SimpleSync - Uploading World"));
    }

    @Override
    protected void init() {
        super.init();
        int buttonWidth = 200;
        int buttonHeight = 20;
        int x = (this.width - buttonWidth) / 2;
        int y = this.height / 2 + 30;

        this.cancelQuitButton = Button.builder(
                Component.literal("Cancel Sync & Quit Immediately"),
                button -> {
                    CloudSyncManager.getInstance().cancelCurrentSync();
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) {
                        client.stop();
                    }
                }
        ).bounds(x, y, buttonWidth, buttonHeight).build();

        this.addRenderableWidget(this.cancelQuitButton);
    }

    @Override
    public void tick() {
        super.tick();
        SyncStatus status = CloudSyncManager.getInstance().getStatus();
        if (!status.isBusy()) {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.stop();
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

        int titleX = (this.width - this.font.width(titleText)) / 2;
        int statusX = (this.width - this.font.width(statusText)) / 2;
        String closeMsg = "Closing Minecraft automatically once complete...";
        int closeX = (this.width - this.font.width(closeMsg)) / 2;

        extractor.text(this.font, titleText, titleX, this.height / 2 - 40, 0xFFFFFF, true);
        extractor.text(this.font, statusText, statusX, this.height / 2 - 15, 0x4FC3F7, true);
        extractor.text(this.font, closeMsg, closeX, this.height / 2 + 5, 0xAAAAAA, true);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
