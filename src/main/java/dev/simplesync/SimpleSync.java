package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import dev.simplesync.config.SyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Locale;

public class SimpleSync implements ModInitializer {
    public static final String MOD_ID = "simplesync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile String lastWorldName = null;
    public static volatile boolean needsTitleScreenSync = true;

    @Override
    public void onInitialize() {
        LOGGER.info("[SimpleSync] Initializing...");

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (isIntegratedServer(server)) {
                lastWorldName = server.getWorldPath(LevelResource.ROOT)
                        .normalize().getFileName().toString();
                LOGGER.info("[SimpleSync] World starting: {}", lastWorldName);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (isIntegratedServer(server) && lastWorldName != null) {
                needsTitleScreenSync = true;
                if (SyncConfig.load().autoSyncOnExit) {
                    LOGGER.info("[SimpleSync] World stopped: {}. Triggering upload...", lastWorldName);
                    CloudSyncManager.getInstance().uploadWorldAsync(lastWorldName);
                } else {
                    LOGGER.info("[SimpleSync] World stopped: {}, but autoSyncOnExit is disabled. Skipping auto-upload.", lastWorldName);
                }
            }
        });
    }

    private static boolean isIntegratedServer(MinecraftServer server) {
        return !server.isDedicatedServer();
    }

    public static String getLastWorldName() {
        return lastWorldName;
    }

    public static boolean openUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!isAllowedGoogleUrl(uri)) {
                LOGGER.warn("[SimpleSync] Refused to open unexpected Google URL: {}", url);
                return false;
            }
            return openUriRobust(uri);
        } catch (Exception e) {
            LOGGER.error("[SimpleSync] Failed to open URL completely: {}", url, e);
            return false;
        }
    }

    public static boolean openUriRobust(URI uri) {
        if (uri == null) return false;
        LOGGER.info("[SimpleSync] Attempting to open URI robustly: {}", uri);

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isLinux = osName.contains("linux") || osName.contains("unix");

        if (isLinux) {
            if (launchSanitizedLinux("xdg-open", uri.toString())) {
                LOGGER.info("[SimpleSync] Opened URI via sanitized xdg-open: {}", uri);
                return true;
            }
            if (launchSanitizedLinux("gio", "open", uri.toString())) {
                LOGGER.info("[SimpleSync] Opened URI via sanitized gio open: {}", uri);
                return true;
            }
        }

        try {
            net.minecraft.util.Util.getPlatform().openUri(uri);
            LOGGER.info("[SimpleSync] Opened URI via Minecraft Util.openUri: {}", uri);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[SimpleSync] Minecraft Util.openUri failed: {}", e.getMessage());
        }

        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
                LOGGER.info("[SimpleSync] Opened URI via Java AWT Desktop: {}", uri);
                return true;
            }
        } catch (Exception e) {
            LOGGER.warn("[SimpleSync] Java AWT Desktop browse failed: {}", e.getMessage());
        }

        return false;
    }

    public static boolean openFileRobust(java.io.File file) {
        if (file == null) return false;
        LOGGER.info("[SimpleSync] Attempting to open file/folder robustly: {}", file.getAbsolutePath());

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isLinux = osName.contains("linux") || osName.contains("unix");

        if (isLinux) {
            if (launchSanitizedLinux("xdg-open", file.getAbsolutePath())) {
                LOGGER.info("[SimpleSync] Opened file via sanitized xdg-open: {}", file.getAbsolutePath());
                return true;
            }
            if (launchSanitizedLinux("gio", "open", file.getAbsolutePath())) {
                LOGGER.info("[SimpleSync] Opened file via sanitized gio open: {}", file.getAbsolutePath());
                return true;
            }
        }

        try {
            net.minecraft.util.Util.getPlatform().openUri(file.toURI());
            LOGGER.info("[SimpleSync] Opened file via Minecraft Util.openUri: {}", file.toURI());
            return true;
        } catch (Exception e) {
            LOGGER.warn("[SimpleSync] Minecraft Util.openUri failed for file: {}", e.getMessage());
        }

        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                if (java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(file);
                    LOGGER.info("[SimpleSync] Opened file via Java AWT Desktop.open: {}", file.getAbsolutePath());
                    return true;
                } else if (java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(file.toURI());
                    LOGGER.info("[SimpleSync] Opened file via Java AWT Desktop.browse: {}", file.getAbsolutePath());
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[SimpleSync] Java AWT Desktop open/browse failed for file: {}", e.getMessage());
        }

        return false;
    }

    private static boolean launchSanitizedLinux(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().remove("LD_LIBRARY_PATH");
            pb.environment().remove("LD_PRELOAD");
            pb.environment().remove("APPIMAGE");
            pb.environment().remove("APPDIR");
            Process p = pb.start();
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
