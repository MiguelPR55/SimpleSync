package dev.simplesync.ui;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.SyncStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Screen shown when there is a conflict between the local and cloud versions of a world.
 * Allows the user to choose which version to keep.
 */
public class SyncConflictScreen extends Screen {

    private final String worldName;
    private final Runnable onUseCloud;
    private final Runnable onKeepLocal;
    private final String formattedLocalDate;
    private final String formattedCloudDate;
    private final java.util.concurrent.atomic.AtomicBoolean resolved = new java.util.concurrent.atomic.AtomicBoolean(false);

    public SyncConflictScreen(String worldName, long localTimestamp, long cloudTimestamp,
                              Runnable onUseCloud, Runnable onKeepLocal) {
        super(Component.translatable("simplesync.conflict.title"));
        this.worldName = worldName;
        this.onUseCloud = onUseCloud;
        this.onKeepLocal = onKeepLocal;

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        String unknownText = Component.translatable("simplesync.conflict.unknown").getString();
        this.formattedLocalDate = localTimestamp > 0 ? dtf.format(Instant.ofEpochMilli(localTimestamp)) : unknownText;
        this.formattedCloudDate = cloudTimestamp > 0 ? dtf.format(Instant.ofEpochMilli(cloudTimestamp)) : unknownText;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonWidth = 200;
        int buttonY = this.height / 2 + 30;

        this.addRenderableWidget(Button.builder(
                        Component.translatable("simplesync.conflict.use_cloud"),
                        button -> {
                            if (resolved.compareAndSet(false, true)) {
                                CloudSyncManager.getInstance().setStatus(SyncStatus.DOWNLOADING, worldName);
                                onUseCloud.run();
                                this.onClose();
                            }
                        })
                .bounds(centerX - buttonWidth - 5, buttonY, buttonWidth, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("simplesync.conflict.keep_local"),
                        button -> {
                            if (resolved.compareAndSet(false, true)) {
                                onKeepLocal.run();
                                this.onClose();
                            }
                        })
                .bounds(centerX + 5, buttonY, buttonWidth, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("simplesync.conflict.cancel"),
                        button -> {
                            if (resolved.compareAndSet(false, true)) {
                                onKeepLocal.run();
                                this.onClose();
                            }
                        })
                .bounds(centerX - 100, buttonY + 30, 200, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int y = this.height / 2 - 60;

        extractor.centeredText(this.font,
                Component.translatable("simplesync.conflict.title"), centerX, y, 0xFFFFFF);

        y += 20;

        extractor.centeredText(this.font,
                Component.translatable("simplesync.conflict.world", worldName), centerX, y, 0xAAAAAA);

        y += 25;

        extractor.centeredText(this.font,
                Component.translatable("simplesync.conflict.local_version", formattedLocalDate),
                centerX, y, 0x81C784);

        y += 15;

        extractor.centeredText(this.font,
                Component.translatable("simplesync.conflict.cloud_version", formattedCloudDate),
                centerX, y, 0x4FC3F7);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        if (resolved.compareAndSet(false, true)) {
            onKeepLocal.run();
        }
        super.onClose();
    }
}
