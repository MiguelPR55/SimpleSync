package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import dev.simplesync.config.SyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;

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
                lastWorldName = server.getSavePath(WorldSavePath.ROOT)
                        .normalize().getFileName().toString();
                LOGGER.info("[SimpleSync] World starting: {}", lastWorldName);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (isIntegratedServer(server) && lastWorldName != null) {
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
        return !server.isDedicated();
    }

    public static String getLastWorldName() {
        return lastWorldName;
    }

    public static void openUrl(String url) {
        // Security: Only allow http/https URLs to prevent arbitrary URI scheme attacks
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            LOGGER.warn("[SimpleSync] Refused to open URL with disallowed scheme: {}", url);
            return;
        }

        try {
            URI uri = URI.create(url);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            } else {
                // Fallback to Minecraft's built-in utility
                net.minecraft.util.Util.getOperatingSystem().open(uri);
            }
            LOGGER.info("[SimpleSync] Opened URL in browser: {}", url);
        } catch (Exception e) {
            LOGGER.warn("[SimpleSync] Failed to open URL via Desktop API, falling back to Minecraft Util API: {}", e.getMessage());
            try {
                net.minecraft.util.Util.getOperatingSystem().open(URI.create(url));
            } catch (Exception ex) {
                LOGGER.error("[SimpleSync] Failed to open URL completely: {}", url, ex);
            }
        }
    }
}
