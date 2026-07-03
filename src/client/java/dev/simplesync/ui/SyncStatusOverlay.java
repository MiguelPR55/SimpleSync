package dev.simplesync.ui;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.StatusSnapshot;
import dev.simplesync.sync.SyncStatus;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * HUD overlay that displays the current sync status.
 * Shows an animated icon and message in the top-right corner of the screen.
 */
public class SyncStatusOverlay implements HudRenderCallback {

    private static final SyncStatusOverlay INSTANCE = new SyncStatusOverlay();
    private static final int DISPLAY_DURATION_MS = 5000;
    private static final int FADE_DURATION_MS = 1000;
    private static final int PADDING = 6;
    private static final int MARGIN = 10;
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};

    public static SyncStatusOverlay getInstance() {
        return INSTANCE;
    }

    @Override
    public void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
        CloudSyncManager manager = CloudSyncManager.getInstance();
        StatusSnapshot snapshot = manager.getStatusSnapshot();
        SyncStatus status = snapshot.status();

        if (status == SyncStatus.IDLE) {
            return;
        }

        long elapsed = System.currentTimeMillis() - snapshot.timestamp();

        // Auto-hide DONE/ERROR status after a while
        if (status == SyncStatus.DONE || status == SyncStatus.ERROR) {
            if (elapsed > DISPLAY_DURATION_MS + FADE_DURATION_MS) {
                manager.clearStatus();
                return;
            }
        }

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        String statusText = Text.translatable(status.getTranslationKey()).getString();
        String detail = snapshot.detail();
        String message = detail != null && !detail.isEmpty() ? statusText + " (" + detail + ")" : statusText;
        String icon = getStatusIcon(status);
        String displayText = icon.isEmpty() ? message : icon + " " + message;

        int textWidth = textRenderer.getWidth(displayText);
        int screenWidth = drawContext.getScaledWindowWidth();

        int x = screenWidth - textWidth - MARGIN - PADDING * 2;
        int y = MARGIN;

        // Calculate opacity for fade effect
        int alpha = 255;
        if (status == SyncStatus.DONE || status == SyncStatus.ERROR) {
            if (elapsed > DISPLAY_DURATION_MS) {
                float fadeProgress = (float) (elapsed - DISPLAY_DURATION_MS) / FADE_DURATION_MS;
                alpha = (int) (255 * (1.0f - Math.min(fadeProgress, 1.0f)));
            }
        }

        if (alpha <= 0) {
            return;
        }

        // Background color (semi-transparent dark)
        int bgColor = (Math.min(alpha, 180) << 24) | 0x1A1A2E;
        // Text color based on status
        int textColor = getStatusColor(status) | (alpha << 24);

        // Draw background
        drawContext.fill(
                x - PADDING,
                y - PADDING,
                x + textWidth + PADDING,
                y + textRenderer.fontHeight + PADDING,
                bgColor
        );

        // Draw border accent
        int borderColor = getStatusColor(status) | (Math.min(alpha, 120) << 24);
        drawContext.fill(
                x - PADDING - 2,
                y - PADDING,
                x - PADDING,
                y + textRenderer.fontHeight + PADDING,
                borderColor
        );

        // Draw text
        drawContext.drawText(textRenderer, displayText, x, y, textColor, true);
    }

    private String getStatusIcon(SyncStatus status) {
        return switch (status) {
            case AUTHENTICATING -> getSpinner();
            case CHECKING -> getSpinner();
            case DOWNLOADING -> "\u2B07";
            case UPLOADING -> "\u2B06";
            case COMPRESSING -> getSpinner();
            case EXTRACTING -> getSpinner();
            case DONE -> "\u2714";
            case ERROR -> "\u2718";
            case CONFLICT -> "\u26A0";
            case IDLE -> "";
        };
    }

    private String getSpinner() {
        int frame = (int) ((System.currentTimeMillis() / 120) % SPINNER_FRAMES.length);
        if (frame < 0) {
            frame += SPINNER_FRAMES.length;
        }
        return SPINNER_FRAMES[frame];
    }

    private int getStatusColor(SyncStatus status) {
        return switch (status) {
            case DOWNLOADING, UPLOADING, CHECKING -> 0x4FC3F7;
            case AUTHENTICATING -> 0xFFB74D;
            case COMPRESSING, EXTRACTING -> 0x81C784;
            case DONE -> 0x66BB6A;
            case ERROR -> 0xEF5350;
            case CONFLICT -> 0xFFA726;
            case IDLE -> 0xFFFFFF;
        };
    }
}
