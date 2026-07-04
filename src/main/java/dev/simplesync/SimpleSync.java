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
            net.minecraft.util.Util.getPlatform().openUri(uri);
            LOGGER.info("[SimpleSync] Opened URL in browser: {}", url);
            return true;
        } catch (Exception e) {
            LOGGER.error("[SimpleSync] Failed to open URL completely: {}", url, e);
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
                && ("accounts.google.com".equals(host) || "console.cloud.google.com".equals(host));
    }
}
