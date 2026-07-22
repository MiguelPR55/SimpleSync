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
        preloadClasses();

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

    private static void preloadClasses() {
        String[] classes = {
            "dev.simplesync.cloud.TokenStore",
            "dev.simplesync.cloud.TokenStore$TokenData",
            "dev.simplesync.cloud.DeviceCodeAuthenticator",
            "dev.simplesync.cloud.GoogleDriveProvider",
            "dev.simplesync.cloud.CloudSyncManager",
            "dev.simplesync.util.RetryUtil",
            "dev.simplesync.sync.WorldSyncTask",
            "dev.simplesync.sync.WorldMetadata",
            "dev.simplesync.sync.StatusSnapshot",
            "dev.simplesync.sync.SyncStatus",
            "dev.simplesync.shadow.org.apache.commons.compress.archivers.tar.TarArchiveOutputStream",
            "dev.simplesync.shadow.org.apache.commons.compress.archivers.zip.ZipEncodingHelper",
            "dev.simplesync.shadow.org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream"
        };
        for (String cls : classes) {
            try {
                Class.forName(cls, true, SimpleSync.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                LOGGER.warn("[SimpleSync] Preloading failed for class: {}", cls);
            }
        }
    }

    private static boolean isIntegratedServer(MinecraftServer server) {
        return !server.isDedicatedServer();
    }

    public static String getLastWorldName() {
        return lastWorldName;
    }

    public static boolean openUrl(String url) {
        return dev.simplesync.util.DesktopUtil.openUrl(url);
    }

    public static boolean openUriRobust(URI uri) {
        return dev.simplesync.util.DesktopUtil.openUriRobust(uri);
    }

    public static boolean openFileRobust(java.io.File file) {
        return dev.simplesync.util.DesktopUtil.openFileRobust(file);
    }
}
