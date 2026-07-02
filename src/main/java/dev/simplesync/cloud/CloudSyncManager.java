package dev.simplesync.cloud;

import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.SyncStatus;
import dev.simplesync.sync.WorldMetadata;
import dev.simplesync.sync.WorldSyncTask;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates all cloud synchronization operations.
 * All heavy operations run on a background thread to avoid blocking the game.
 */
public class CloudSyncManager {

    private static CloudSyncManager instance;

    private final ExecutorService executor;
    private final AtomicReference<SyncStatus> currentStatus;
    private final AtomicReference<String> statusMessage;
    private CloudProvider provider;
    private long statusTimestamp;
    private Path savesDirectory;

    public void setSavesDirectory(Path savesDirectory) {
        this.savesDirectory = savesDirectory;
    }

    private CloudSyncManager() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SimpleSync-Worker");
            t.setDaemon(true);
            return t;
        });
        this.currentStatus = new AtomicReference<>(SyncStatus.IDLE);
        this.statusMessage = new AtomicReference<>("");
        this.statusTimestamp = 0;
    }

    public static synchronized CloudSyncManager getInstance() {
        if (instance == null) {
            instance = new CloudSyncManager();
        }
        return instance;
    }

    public CloudProvider getProvider() {
        if (provider == null) {
            provider = new GoogleDriveProvider();
        }
        return provider;
    }

    /**
     * Downloads all worlds from the cloud that are newer than local copies.
     * Called when the game starts (from TitleScreen).
     */
    public CompletableFuture<Void> syncAllWorldsFromCloud() {
        return CompletableFuture.runAsync(() -> {
            try {
                CloudProvider cloud = getProvider();
                if (!cloud.isAuthenticated()) {
                    setStatus(SyncStatus.AUTHENTICATING, "Connecting to " + cloud.getName() + "...");
                    cloud.authenticate();
                }

                setStatus(SyncStatus.CHECKING, "Checking cloud for updates...");

                List<WorldMetadata> cloudWorlds = cloud.listWorlds();
                if (cloudWorlds.isEmpty()) {
                    SimpleSync.LOGGER.info("[SimpleSync] No worlds found in cloud");
                    setStatus(SyncStatus.DONE, "No worlds to sync");
                    return;
                }

                Path savesDir = getSavesDirectory();
                SyncConfig config = SyncConfig.load();
                int downloadCount = 0;

                for (WorldMetadata cloudWorld : cloudWorlds) {
                    Path worldFolder = savesDir.resolve(cloudWorld.worldName());
                    long localTimestamp = config.getLastSyncTimestamp(cloudWorld.worldName());

                    if (cloudWorld.lastModified() > localTimestamp) {
                        setStatus(SyncStatus.DOWNLOADING, "Downloading: " + cloudWorld.worldName());

                        Path tempZip = getTempDir().resolve(cloudWorld.worldName() + ".zip");
                        cloud.download(cloudWorld.worldName(), tempZip);

                        setStatus(SyncStatus.EXTRACTING, "Extracting: " + cloudWorld.worldName());
                        WorldSyncTask.extractWorld(tempZip, worldFolder);

                        config.setLastSyncTimestamp(cloudWorld.worldName(), cloudWorld.lastModified());

                        Files.deleteIfExists(tempZip);
                        downloadCount++;

                        SimpleSync.LOGGER.info("[SimpleSync] Downloaded world: {}", cloudWorld.worldName());
                    } else {
                        SimpleSync.LOGGER.info("[SimpleSync] World '{}' is up to date", cloudWorld.worldName());
                    }
                }

                config.save();

                if (downloadCount > 0) {
                    setStatus(SyncStatus.DONE, downloadCount + " world(s) synced from cloud");
                } else {
                    setStatus(SyncStatus.DONE, "All worlds up to date");
                }

            } catch (Exception e) {
                SimpleSync.LOGGER.error("[SimpleSync] Sync from cloud failed", e);
                setStatus(SyncStatus.ERROR, "Sync failed: " + e.getMessage());
            }
        }, executor);
    }

    /**
     * Uploads a specific world to the cloud.
     * Called when the player exits a world ("Save and Quit").
     */
    public CompletableFuture<Void> uploadWorldAsync(String worldName) {
        return CompletableFuture.runAsync(() -> {
            try {
                CloudProvider cloud = getProvider();
                if (!cloud.isAuthenticated()) {
                    setStatus(SyncStatus.AUTHENTICATING, "Connecting to " + cloud.getName() + "...");
                    cloud.authenticate();
                }

                Path savesDir = getSavesDirectory();
                Path worldFolder = savesDir.resolve(worldName);

                if (!Files.isDirectory(worldFolder)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] World folder not found: {}", worldFolder);
                    setStatus(SyncStatus.ERROR, "World folder not found: " + worldName);
                    return;
                }

                setStatus(SyncStatus.COMPRESSING, "Compressing: " + worldName);
                Path tempZip = getTempDir().resolve(worldName + ".zip");
                WorldSyncTask.compressWorld(worldFolder, tempZip);

                setStatus(SyncStatus.UPLOADING, "Uploading: " + worldName);
                cloud.upload(worldName, tempZip);

                SyncConfig config = SyncConfig.load();
                config.setLastSyncTimestamp(worldName, System.currentTimeMillis());
                config.save();

                Files.deleteIfExists(tempZip);

                setStatus(SyncStatus.DONE, "Uploaded: " + worldName);
                SimpleSync.LOGGER.info("[SimpleSync] Successfully uploaded world: {}", worldName);

            } catch (Exception e) {
                SimpleSync.LOGGER.error("[SimpleSync] Upload failed for world: {}", worldName, e);
                setStatus(SyncStatus.ERROR, "Upload failed: " + e.getMessage());
            }
        }, executor);
    }

    // ─── Status Management ──────────────────────────────────────────────────

    public SyncStatus getStatus() {
        return currentStatus.get();
    }

    public String getStatusMessage() {
        return statusMessage.get();
    }

    public long getStatusTimestamp() {
        return statusTimestamp;
    }

    public void setStatus(SyncStatus status, String message) {
        this.currentStatus.set(status);
        this.statusMessage.set(message);
        this.statusTimestamp = System.currentTimeMillis();
        SimpleSync.LOGGER.debug("[SimpleSync] Status: {} - {}", status, message);
    }

    public void clearStatus() {
        setStatus(SyncStatus.IDLE, "");
    }

    // ─── Path Helpers ────────────────────────────────────────────────────────

    private Path getSavesDirectory() {
        if (savesDirectory == null) {
            savesDirectory = Path.of("saves");
        }
        return savesDirectory;
    }

    private Path getTempDir() throws IOException {
        Path tempDir = SyncConfig.getConfigDir().resolve("temp");
        Files.createDirectories(tempDir);
        return tempDir;
    }
}
