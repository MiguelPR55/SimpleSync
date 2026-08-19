package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.util.SyncLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class SimpleSync implements ModInitializer {

    public static final String MOD_ID = "simplesync";
    private static volatile String lastWorldName = null;
    private static volatile String activeRunningWorld = null;
    public static final java.util.concurrent.atomic.AtomicBoolean needsTitleScreenSync = new java.util.concurrent.atomic.AtomicBoolean(true);

    @Override
    public void onInitialize() {
        SyncLogger.info("[SimpleSync] Initializing...");
        preloadClasses();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (!server.isDedicatedServer()) {
                String name = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
                if (dev.simplesync.sync.WorldSyncTask.isWorldNameSafe(name)) {
                    lastWorldName = name;
                    activeRunningWorld = name;
                    SyncLogger.info("[SimpleSync] World starting: {}", lastWorldName);
                } else {
                    SyncLogger.warn("[SimpleSync] World folder name '{}' is unsafe for cloud sync. Skipping auto-sync.", name);
                    lastWorldName = null;
                    activeRunningWorld = null;
                }
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            activeRunningWorld = null;
            if (server.isDedicatedServer() || lastWorldName == null) return;
            needsTitleScreenSync.set(false);
            String stoppedWorld = lastWorldName;
            CloudSyncManager.getInstance().markWorldUnsynchronized(stoppedWorld);

            Path gameRoot = CloudSyncManager.getInstance().getGameRootDir();
            dev.simplesync.compat.litematica.LitematicaIntegration.onWorldExit(stoppedWorld, gameRoot);

            SyncConfig config = SyncConfig.load();
            if (config.autoSyncOnExit) {
                SyncLogger.info("[SimpleSync] World stopped: {}. Uploading...", stoppedWorld);
                CloudSyncManager.getInstance().uploadWorldAsync(stoppedWorld);
            } else {
                CloudSyncManager.getInstance().markWorldSynchronized(stoppedWorld);
            }
            if (config.syncSchematics || config.syncMasaConfigs) {
                CloudSyncManager.getInstance().syncExtraFilesAsync();
            }
        });
    }

    private static void preloadClasses() {
        Class<?>[] classes = {
            dev.simplesync.cloud.TokenStore.class,
            dev.simplesync.cloud.DeviceCodeAuthenticator.class,
            dev.simplesync.cloud.GoogleDriveProvider.class,
            dev.simplesync.cloud.CloudSyncManager.class,
            dev.simplesync.compat.litematica.LitematicaPathNormalizer.class,
            dev.simplesync.compat.litematica.LitematicaIntegration.class,
            dev.simplesync.util.RetryUtil.class,
            dev.simplesync.util.SyncLogger.class,
            dev.simplesync.sync.WorldSyncTask.class,
            dev.simplesync.sync.WorldArchiver.class,
            dev.simplesync.sync.WorldMetadata.class,
            dev.simplesync.sync.StatusSnapshot.class,
            dev.simplesync.sync.SyncStatus.class,
            java.util.concurrent.CompletableFuture.class,
            java.net.http.HttpClient.class,
            java.net.http.HttpRequest.class,
            java.net.http.HttpResponse.class,
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.class,
            com.github.luben.zstd.ZstdOutputStream.class,
            com.github.luben.zstd.ZstdInputStream.class
        };
        for (Class<?> cls : classes) {
            try {
                Class.forName(cls.getName(), true, SimpleSync.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {}
        }
    }

    public static String getLastWorldName() { return lastWorldName; }
    public static String getActiveRunningWorld() { return activeRunningWorld; }
    public static boolean isWorldRunning(String name) {
        return name != null && name.equals(activeRunningWorld);
    }
}
