package dev.simplesync.util;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Robust utility for opening URLs and local files/directories across operating systems
 * (Windows, Linux, macOS, Flatpak, Steam Deck, Wayland, X11).
 */
public final class DesktopUtil {

    private DesktopUtil() {}

    /**
     * Opens a URL in the user's default browser safely and asynchronously.
     * Validates that the URL belongs to an allowed Google domain.
     *
     * @param url String representation of the URL to open
     * @return true if the URL request was scheduled
     */
    public static boolean openUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            if (!isAllowedGoogleUrl(uri)) {
                SyncLogger.warn("[SimpleSync] Refused to open unexpected non-Google URL: {}", url);
                return false;
            }
            return openUriRobust(uri);
        } catch (Exception e) {
            SyncLogger.error("[SimpleSync] Failed to parse URL: {}", url, e);
            return false;
        }
    }

    /**
     * Opens a URI in the default web browser across Windows, Linux (Flatpak, X11, Wayland), and macOS.
     */
    public static boolean openUriRobust(URI uri) {
        if (uri == null) return false;
        String urlString = uri.toString();
        SyncLogger.info("[SimpleSync] Opening URI: {}", urlString);

        // Run asynchronously to ensure the render thread never blocks
        CompletableFuture.runAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

            if (os.contains("win")) {
                if (openWindowsUrl(urlString, uri)) return;
            } else if (os.contains("mac") || os.contains("darwin")) {
                if (openMacUrl(urlString, uri)) return;
            } else {
                // Linux / BSD / Solaris / Steam Deck / Flatpak
                if (openLinuxUrl(urlString, uri)) return;
            }

            // Universal Fallbacks
            if (openMinecraftPlatformUri(uri)) return;
            if (openJavaAwtBrowse(uri)) return;

            SyncLogger.error("[SimpleSync] All attempts to open URI failed: {}", urlString);
        });

        return true;
    }

    /**
     * Opens a local file or directory in the default file explorer across platforms.
     */
    public static boolean openFileRobust(File file) {
        if (file == null) return false;
        String absolutePath = file.getAbsolutePath();
        SyncLogger.info("[SimpleSync] Opening local file/directory: {}", absolutePath);

        CompletableFuture.runAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

            if (os.contains("win")) {
                if (openWindowsFile(absolutePath, file)) return;
            } else if (os.contains("mac") || os.contains("darwin")) {
                if (openMacFile(absolutePath, file)) return;
            } else {
                if (openLinuxFile(absolutePath, file)) return;
            }

            if (openMinecraftPlatformUri(file.toURI())) return;
            if (openJavaAwtFile(file)) return;

            SyncLogger.error("[SimpleSync] All attempts to open file failed: {}", absolutePath);
        });

        return true;
    }

    // ─── Windows Handlers ─────────────────────────────────────────────────

    private static boolean openWindowsUrl(String url, URI uri) {
        // 1. Minecraft native platform
        if (openMinecraftPlatformUri(uri)) return true;

        // 2. Java AWT
        if (openJavaAwtBrowse(uri)) return true;

        // 3. rundll32 FileProtocolHandler
        if (runCommandWithTimeout("rundll32", "url.dll,FileProtocolHandler", url)) {
            SyncLogger.info("[SimpleSync] Opened URL via rundll32: {}", url);
            return true;
        }

        // 4. cmd.exe start
        if (runCommandWithTimeout("cmd.exe", "/c", "start", "\"\"", url)) {
            SyncLogger.info("[SimpleSync] Opened URL via cmd.exe start: {}", url);
            return true;
        }

        // 5. PowerShell Start-Process
        if (runCommandWithTimeout("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Start-Process", "\"" + url + "\"")) {
            SyncLogger.info("[SimpleSync] Opened URL via PowerShell: {}", url);
            return true;
        }

        return false;
    }

    private static boolean openWindowsFile(String path, File file) {
        if (runCommandWithTimeout("explorer.exe", path)) {
            SyncLogger.info("[SimpleSync] Opened file via explorer.exe: {}", path);
            return true;
        }
        if (openMinecraftPlatformUri(file.toURI())) return true;
        if (openJavaAwtFile(file)) return true;
        return false;
    }

    // ─── Linux / Flatpak / Steam Deck Handlers ────────────────────────────

    private static boolean openLinuxUrl(String url, URI uri) {
        boolean isFlatpak = isRunningInFlatpak();

        // 1. If running in Flatpak (e.g. Prism Launcher Flatpak, Modrinth Flatpak, Steam Deck Game Mode)
        if (isFlatpak) {
            if (runSanitizedCommand("flatpak-spawn", "--host", "xdg-open", url)) {
                SyncLogger.info("[SimpleSync] Opened URL via flatpak-spawn xdg-open: {}", url);
                return true;
            }
            if (runSanitizedCommand("flatpak-spawn", "--host", "gio", "open", url)) {
                SyncLogger.info("[SimpleSync] Opened URL via flatpak-spawn gio: {}", url);
                return true;
            }
        }

        // 2. Native xdg-open with sanitized environment (prevents AppImage/Flatpak LD_LIBRARY_PATH pollution)
        if (runSanitizedCommand("xdg-open", url)) {
            SyncLogger.info("[SimpleSync] Opened URL via xdg-open: {}", url);
            return true;
        }

        // 3. Native gio open
        if (runSanitizedCommand("gio", "open", url)) {
            SyncLogger.info("[SimpleSync] Opened URL via gio open: {}", url);
            return true;
        }

        // 4. KDE kfmclient fallback
        if (runSanitizedCommand("kfmclient", "openURL", url)) {
            SyncLogger.info("[SimpleSync] Opened URL via kfmclient: {}", url);
            return true;
        }

        // 5. WSL wslview fallback (if running in WSL)
        if (runSanitizedCommand("wslview", url)) {
            SyncLogger.info("[SimpleSync] Opened URL via wslview: {}", url);
            return true;
        }

        // 6. Minecraft platform fallback
        if (openMinecraftPlatformUri(uri)) return true;

        // 7. Java AWT fallback
        if (openJavaAwtBrowse(uri)) return true;

        return false;
    }

    private static boolean openLinuxFile(String path, File file) {
        boolean isFlatpak = isRunningInFlatpak();
        if (isFlatpak) {
            if (runSanitizedCommand("flatpak-spawn", "--host", "xdg-open", path)) {
                SyncLogger.info("[SimpleSync] Opened file via flatpak-spawn xdg-open: {}", path);
                return true;
            }
        }
        if (runSanitizedCommand("xdg-open", path)) {
            SyncLogger.info("[SimpleSync] Opened file via xdg-open: {}", path);
            return true;
        }
        if (runSanitizedCommand("gio", "open", path)) {
            SyncLogger.info("[SimpleSync] Opened file via gio open: {}", path);
            return true;
        }
        if (openMinecraftPlatformUri(file.toURI())) return true;
        if (openJavaAwtFile(file)) return true;
        return false;
    }

    // ─── macOS Handlers ───────────────────────────────────────────────────

    private static boolean openMacUrl(String url, URI uri) {
        if (runCommandWithTimeout("/usr/bin/open", url)) {
            SyncLogger.info("[SimpleSync] Opened URL via /usr/bin/open: {}", url);
            return true;
        }
        if (openMinecraftPlatformUri(uri)) return true;
        if (openJavaAwtBrowse(uri)) return true;
        return false;
    }

    private static boolean openMacFile(String path, File file) {
        if (runCommandWithTimeout("/usr/bin/open", path)) {
            SyncLogger.info("[SimpleSync] Opened file via /usr/bin/open: {}", path);
            return true;
        }
        if (openMinecraftPlatformUri(file.toURI())) return true;
        if (openJavaAwtFile(file)) return true;
        return false;
    }

    // ─── Platform & AWT Helpers ───────────────────────────────────────────

    private static boolean openMinecraftPlatformUri(URI uri) {
        try {
            net.minecraft.util.Util.getPlatform().openUri(uri);
            SyncLogger.info("[SimpleSync] Opened URI via Minecraft platform: {}", uri);
            return true;
        } catch (Throwable t) {
            SyncLogger.warn("[SimpleSync] Minecraft platform openUri failed: {}", t.getMessage());
            return false;
        }
    }

    private static boolean openJavaAwtBrowse(URI uri) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(uri);
                    SyncLogger.info("[SimpleSync] Opened URI via java.awt.Desktop browse: {}", uri);
                    return true;
                }
            }
        } catch (Throwable t) {
            SyncLogger.warn("[SimpleSync] java.awt.Desktop browse failed: {}", t.getMessage());
        }
        return false;
    }

    private static boolean openJavaAwtFile(File file) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                    desktop.open(file);
                    SyncLogger.info("[SimpleSync] Opened file via java.awt.Desktop open: {}", file);
                    return true;
                } else if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(file.toURI());
                    SyncLogger.info("[SimpleSync] Opened file via java.awt.Desktop browse: {}", file);
                    return true;
                }
            }
        } catch (Throwable t) {
            SyncLogger.warn("[SimpleSync] java.awt.Desktop open failed: {}", t.getMessage());
        }
        return false;
    }

    // ─── Process Utilities ────────────────────────────────────────────────

    private static boolean runSanitizedCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().remove("LD_LIBRARY_PATH");
            pb.environment().remove("LD_PRELOAD");
            pb.environment().remove("APPIMAGE");
            pb.environment().remove("APPDIR");
            pb.environment().remove("PYTHONPATH");
            pb.environment().remove("PERLLIB");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean exited = p.waitFor(2, TimeUnit.SECONDS);
            if (exited) {
                return p.exitValue() == 0;
            }
            // If it didn't exit in 2 seconds, it launched a persistent GUI browser window
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean runCommandWithTimeout(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean exited = p.waitFor(2, TimeUnit.SECONDS);
            if (exited) {
                return p.exitValue() == 0;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isRunningInFlatpak() {
        try {
            if (System.getenv("FLATPAK_ID") != null) return true;
            return Files.exists(Path.of("/.flatpak-info"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isAllowedGoogleUrl(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(scheme)
                && (host.endsWith(".google.com") || host.endsWith(".googleapis.com") || "google.com".equals(host) || "googleapis.com".equals(host));
    }
}
