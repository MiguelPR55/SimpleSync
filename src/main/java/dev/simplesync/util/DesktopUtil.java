package dev.simplesync.util;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Clean and robust utility for opening URLs and local files across operating systems.
 * Uses Minecraft's native platform handler with Linux/Flatpak and Java AWT fallbacks.
 */
public final class DesktopUtil {

    private DesktopUtil() {}

    /**
     * Opens a URL in the user's default browser safely.
     * Validates that the URL belongs to an allowed Google domain.
     */
    public static boolean openUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            if (!isAllowedGoogleUrl(uri)) {
                SyncLogger.warn("[SimpleSync] Refused to open non-Google URL: {}", url);
                return false;
            }
            return openUri(uri);
        } catch (Exception e) {
            SyncLogger.error("[SimpleSync] Failed to parse URL: {}", url, e);
            return false;
        }
    }

    /**
     * Opens a URI using Minecraft's built-in platform utility, with Flatpak/Linux and AWT fallbacks.
     */
    public static boolean openUri(URI uri) {
        if (uri == null) return false;
        SyncLogger.info("[SimpleSync] Opening URI: {}", uri);

        // 1. Standard Minecraft platform opener (handles Windows, macOS, Linux natively)
        try {
            net.minecraft.util.Util.getPlatform().openUri(uri);
            return true;
        } catch (Throwable ignored) {}

        // 2. Linux / Flatpak / Steam Deck fallback
        if (openLinuxFallback(uri.toString())) {
            return true;
        }

        // 3. Java AWT Desktop fallback
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return true;
            }
        } catch (Throwable ignored) {}

        SyncLogger.error("[SimpleSync] Failed to open URI: {}", uri);
        return false;
    }

    /**
     * Opens a local file or directory using Minecraft's built-in platform utility.
     */
    public static boolean openFile(File file) {
        if (file == null) return false;
        SyncLogger.info("[SimpleSync] Opening file: {}", file.getAbsolutePath());

        // 1. Standard Minecraft platform opener
        try {
            net.minecraft.util.Util.getPlatform().openFile(file);
            return true;
        } catch (Throwable ignored) {}

        // 2. Linux / Flatpak fallback
        if (openLinuxFallback(file.getAbsolutePath())) {
            return true;
        }

        // 3. Java AWT Desktop fallback
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(file);
                    return true;
                } else if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(file.toURI());
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        SyncLogger.error("[SimpleSync] Failed to open file: {}", file);
        return false;
    }

    public static boolean openFileRobust(File file) {
        return openFile(file);
    }

    public static boolean openUriRobust(URI uri) {
        return openUri(uri);
    }

    // ─── Linux / Flatpak Helpers ──────────────────────────────────────────

    private static boolean openLinuxFallback(String target) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") && !os.contains("unix")) return false;

        boolean isFlatpak = System.getenv("FLATPAK_ID") != null || Files.exists(Path.of("/.flatpak-info"));
        String[] cmd = isFlatpak
                ? new String[]{"flatpak-spawn", "--host", "xdg-open", target}
                : new String[]{"xdg-open", target};

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().remove("LD_LIBRARY_PATH");
            pb.environment().remove("LD_PRELOAD");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isAllowedGoogleUrl(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) return false;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(scheme) && (host.endsWith(".google.com") || host.endsWith(".googleapis.com")
                || "google.com".equals(host) || "googleapis.com".equals(host));
    }
}
