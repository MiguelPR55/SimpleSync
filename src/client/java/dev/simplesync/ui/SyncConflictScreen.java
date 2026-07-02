package dev.simplesync.ui;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.SyncStatus;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Screen shown when there is a conflict between the local and cloud versions of a world.
 * Allows the user to choose which version to keep.
 */
public class SyncConflictScreen extends Screen {

    private final String worldName;
    private final long localTimestamp;
    private final long cloudTimestamp;
    private final Runnable onUseCloud;
    private final Runnable onKeepLocal;
    private final String formattedLocalDate;
    private final String formattedCloudDate;
    private boolean resolved = false;

    public SyncConflictScreen(String worldName, long localTimestamp, long cloudTimestamp,
                              Runnable onUseCloud, Runnable onKeepLocal) {
        super(Text.translatable("simplesync.conflict.title"));
        this.worldName = worldName;
        this.localTimestamp = localTimestamp;
        this.cloudTimestamp = cloudTimestamp;
        this.onUseCloud = onUseCloud;
        this.onKeepLocal = onKeepLocal;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String unknownText = Text.translatable("simplesync.conflict.unknown").getString();
        this.formattedLocalDate = localTimestamp > 0 ? sdf.format(new Date(localTimestamp)) : unknownText;
        this.formattedCloudDate = cloudTimestamp > 0 ? sdf.format(new Date(cloudTimestamp)) : unknownText;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonWidth = 200;
        int buttonY = this.height / 2 + 30;

        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("simplesync.conflict.use_cloud"),
                        button -> {
                            resolved = true;
                            CloudSyncManager.getInstance().setStatus(SyncStatus.DOWNLOADING, worldName);
                            onUseCloud.run();
                            this.close();
                        })
                .dimensions(centerX - buttonWidth - 5, buttonY, buttonWidth, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("simplesync.conflict.keep_local"),
                        button -> {
                            resolved = true;
                            onKeepLocal.run();
                            this.close();
                        })
                .dimensions(centerX + 5, buttonY, buttonWidth, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("simplesync.conflict.cancel"),
                        button -> {
                            resolved = true;
                            onKeepLocal.run();
                            this.close();
                        })
                .dimensions(centerX - 100, buttonY + 30, 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int y = this.height / 2 - 60;

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("simplesync.conflict.title"), centerX, y, 0xFFFFFF);

        y += 20;

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("simplesync.conflict.world", worldName), centerX, y, 0xAAAAAA);

        y += 25;

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("simplesync.conflict.local_version", formattedLocalDate),
                centerX, y, 0x81C784);

        y += 15;

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("simplesync.conflict.cloud_version", formattedCloudDate),
                centerX, y, 0x4FC3F7);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (!resolved) {
            resolved = true;
            onKeepLocal.run();
        }
        super.close();
    }
}
