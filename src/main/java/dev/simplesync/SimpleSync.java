package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleSync implements ModInitializer {

    public static final String MOD_ID = "simplesync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile String lastWorldName = null;
    public static final java.util.concurrent.atomic.AtomicBoolean needsTitleScreenSync = new java.util.concurrent.atomic.AtomicBoolean(true);

    @Override
    public void onInitialize() {
        LOGGER.info("[SimpleSync] Initializing...");
        preloadClasses();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (!server.isDedicatedServer()) {
                String name = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
                if (dev.simplesync.sync.WorldSyncTask.isWorldNameSafe(name)) {
                    lastWorldName = name;
                    LOGGER.info("[SimpleSync] World starting: {}", lastWorldName);
                } else {
                    LOGGER.warn("[SimpleSync] World folder name '{}' is unsafe for cloud sync. Skipping auto-sync.", name);
                    lastWorldName = null;
                }
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (server.isDedicatedServer() || lastWorldName == null) return;
            needsTitleScreenSync.set(true);
            SyncConfig config = SyncConfig.load();
            if (config.autoSyncOnExit) {
                LOGGER.info("[SimpleSync] World stopped: {}. Uploading...", lastWorldName);
                CloudSyncManager.getInstance().uploadWorldAsync(lastWorldName);
            }
            if (config.syncSchematics || config.syncMasaConfigs) {
                CloudSyncManager.getInstance().syncExtraFilesAsync();
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
            "dev.simplesync.sync.WorldArchiver",
            "dev.simplesync.sync.WorldMetadata",
            "dev.simplesync.sync.StatusSnapshot",
            "dev.simplesync.sync.SyncStatus",
            "java.util.concurrent.CompletableFuture",
            "java.net.http.HttpClient",
            "java.net.http.HttpRequest",
            "java.net.http.HttpResponse",
            "dev.simplesync.shadow.org.apache.commons.compress.archivers.tar.TarArchiveOutputStream",
            "dev.simplesync.shadow.org.apache.commons.compress.archivers.zip.ZipEncodingHelper",
            "dev.simplesync.shadow.org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream"
        };
        for (String cls : classes) {
            try { Class.forName(cls, true, SimpleSync.class.getClassLoader()); }
            catch (ClassNotFoundException e) { LOGGER.warn("[SimpleSync] Preload failed: {}", cls); }
        }
    }

    public static String getLastWorldName() { return lastWorldName; }
}
