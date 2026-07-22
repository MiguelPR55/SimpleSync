package dev.simplesync.util;

import dev.simplesync.SimpleSync;

import java.io.File;
import java.net.URI;
import java.util.Locale;

/**
 * Utility for robustly opening URLs and local files across different operating systems.
 */
public final class DesktopUtil {

    private DesktopUtil() {
        // Utility class
    }

    @FunctionalInterface
    private interface FallbackAction {
        boolean attempt() throws Exception;
    }

    /**
     * Opens a URL in the user's default browser if it belongs to an allowed domain.
     *
     * @param url String representation of the URL to open
     * @return true if the URL was opened successfully, false otherwise
     */
    public static boolean openUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!isAllowedGoogleUrl(uri)) {
                SimpleSync.LOGGER.warn("[SimpleSync] Refused to open unexpected non-Google URL: {}", url);
                return false;
            }
            return openUriRobust(uri);
        } catch (Exception e) {
            SimpleSync.LOGGER.error("[SimpleSync] Failed to open URL: {}", url, e);
            return false;
        }
    }

    /**
     * Opens a URI using OS-native handlers, Minecraft platform utilities, or Java AWT Desktop.
     *
     * @param uri URI to open
     * @return true if successfully opened
     */
    public static boolean openUriRobust(URI uri) {
        if (uri == null) {
            return false;
        }
        return openTargetRobust(uri.toString(), () -> {
            net.minecraft.util.Util.getPlatform().openUri(uri);
            return true;
        }, () -> {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
                return true;
            }
            return false;
        });
    }

    /**
     * Opens a local file or folder robustly across platforms.
     *
     * @param file Target file or directory
     * @return true if successfully opened
     */
    public static boolean openFileRobust(File file) {
        if (file == null) {
            return false;
        }
        return openTargetRobust(file.getAbsolutePath(), () -> {
            net.minecraft.util.Util.getPlatform().openUri(file.toURI());
            return true;
        }, () -> {
            if (java.awt.Desktop.isDesktopSupported()) {
                if (java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(file);
                    return true;
                } else if (java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(file.toURI());
                    return true;
                }
            }
            return false;
        });
    }

    private static boolean openTargetRobust(String targetPathOrUrl, FallbackAction minecraftAction, FallbackAction awtAction) {
        SimpleSync.LOGGER.info("[SimpleSync] Attempting to open target: {}", targetPathOrUrl);
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isLinuxOrUnix = operatingSystem.contains("linux") || operatingSystem.contains("unix");

        if (isLinuxOrUnix) {
            if (launchSanitizedLinuxProcess("xdg-open", targetPathOrUrl)) {
                SimpleSync.LOGGER.info("[SimpleSync] Opened target via xdg-open: {}", targetPathOrUrl);
                return true;
            }
            if (launchSanitizedLinuxProcess("gio", "open", targetPathOrUrl)) {
                SimpleSync.LOGGER.info("[SimpleSync] Opened target via gio open: {}", targetPathOrUrl);
                return true;
            }
        }

        try {
            if (minecraftAction.attempt()) {
                SimpleSync.LOGGER.info("[SimpleSync] Opened target via Minecraft Util: {}", targetPathOrUrl);
                return true;
            }
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Minecraft Util open failed: {}", e.getMessage());
        }

        try {
            if (awtAction.attempt()) {
                SimpleSync.LOGGER.info("[SimpleSync] Opened target via Java AWT Desktop: {}", targetPathOrUrl);
                return true;
            }
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Java AWT Desktop open failed: {}", e.getMessage());
        }

        return false;
    }

    private static boolean launchSanitizedLinuxProcess(String... command) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().remove("LD_LIBRARY_PATH");
            processBuilder.environment().remove("LD_PRELOAD");
            processBuilder.environment().remove("APPIMAGE");
            processBuilder.environment().remove("APPDIR");
            processBuilder.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAllowedGoogleUrl(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(scheme)
                && (host.endsWith(".google.com") || host.endsWith(".googleapis.com") || "google.com".equals(host) || "googleapis.com".equals(host));
    }
}
