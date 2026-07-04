package dev.simplesync.ui;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.StatusSnapshot;
import dev.simplesync.sync.SyncStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * HUD overlay that displays the current sync status.
 * Shows an animated icon and message in the top-right corner of the screen.
 */
public class SyncStatusOverlay {

    private static final SyncStatusOverlay INSTANCE = new SyncStatusOverlay();
    private static final int DISPLAY_DURATION_MS = 5000;
    private static final int FADE_DURATION_MS = 1000;
    private static final int PADDING = 6;
    private static final int MARGIN = 10;
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};


    public static SyncStatusOverlay getInstance() {
        return INSTANCE;
    }

    private long lastRenderTimeMs = -1;

    public void renderOverlay(GuiGraphicsExtractor extractor) {
        long now = System.currentTimeMillis();
        if (now - lastRenderTimeMs < 5) {
            return;
        }
        lastRenderTimeMs = now;

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

        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        String statusText = Component.translatable(status.getTranslationKey()).getString();
        String detail = snapshot.detail();
        String message = detail != null && !detail.isEmpty() ? statusText + " (" + detail + ")" : statusText;
        String icon = getStatusIcon(status);
        String displayText = icon.isEmpty() ? message : icon + " " + message;

        int textWidth = font.width(displayText);
        int screenWidth = extractor.guiWidth();

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

        if (alpha < 10) {
            return;
        }

        // Background color (semi-transparent dark)
        int bgColor = (Math.min(alpha, 180) << 24) | 0x1A1A2E;
        // Text color based on status
        int textColor = getStatusColor(status) | (alpha << 24);

        // Draw background
        extractor.fill(
                x - PADDING,
                y - PADDING,
                x + textWidth + PADDING,
                y + font.lineHeight + PADDING,
                bgColor
        );

        // Draw border accent
        int borderColor = getStatusColor(status) | (Math.min(alpha, 120) << 24);
        extractor.fill(
                x - PADDING - 2,
                y - PADDING,
                x - PADDING,
                y + font.lineHeight + PADDING,
                borderColor
        );

        // Draw text
        extractor.text(font, displayText, x, y, textColor, true);
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
        long frame = (System.currentTimeMillis() / 150) % SPINNER_FRAMES.length;
        return SPINNER_FRAMES[(int) frame];
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
