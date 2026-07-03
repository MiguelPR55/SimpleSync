package dev.simplesync.cloud;

import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.ConflictCallback;
import dev.simplesync.sync.StatusSnapshot;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates all cloud synchronization operations.
 * All heavy operations run on a background thread to avoid blocking the game.
 */
public class CloudSyncManager {

    private static CloudSyncManager instance;

    private final ExecutorService executor;
    private final AtomicReference<StatusSnapshot> status;
    private CloudProvider provider;
    private Path savesDirectory;
    private ConflictCallback conflictCallback;
    private Runnable conflictCancelCallback;

    public void setConflictCallback(ConflictCallback callback) {
        this.conflictCallback = callback;
    }

    public void setConflictCancelCallback(Runnable callback) {
        this.conflictCancelCallback = callback;
    }

    public void setSavesDirectory(Path savesDirectory) {
        this.savesDirectory = savesDirectory;
    }

    private CloudSyncManager() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SimpleSync-Worker");
            t.setDaemon(true);
            return t;
        });
        this.status = new AtomicReference<>(new StatusSnapshot(SyncStatus.IDLE, "", 0L));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SimpleSync.LOGGER.info("[SimpleSync] JVM shutting down. Awaiting completion of ongoing cloud sync tasks...");
            shutdownAndAwaitTermination();
        }, "SimpleSync-ShutdownHook"));
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
                    setStatus(SyncStatus.AUTHENTICATING, "");
                    cloud.authenticate();
                }

                setStatus(SyncStatus.CHECKING, "");

                List<WorldMetadata> cloudWorlds = cloud.listWorlds();
                if (cloudWorlds.isEmpty()) {
                    SimpleSync.LOGGER.info("[SimpleSync] No worlds found in cloud");
                    setStatus(SyncStatus.DONE, "");
                    return;
                }

                Path savesDir = getSavesDirectory();
                WorldSyncTask.cleanupOrphanedDirectories(savesDir);
                SyncConfig config = SyncConfig.load();
                int downloadCount = 0;

                for (WorldMetadata cloudWorld : cloudWorlds) {
                    try {
                        String worldName = cloudWorld.worldName();
                        if (worldName == null || worldName.contains("..") || worldName.contains("/") || worldName.contains("\\") || worldName.indexOf('\0') >= 0) {
                            SimpleSync.LOGGER.warn("[SimpleSync] Security violation: ignoring cloud world with unsafe name '{}'", worldName);
                            continue;
                        }
                        Path worldFolder = savesDir.resolve(worldName).normalize();
                        if (!worldFolder.startsWith(savesDir.normalize())) {
                            SimpleSync.LOGGER.warn("[SimpleSync] Security violation: path traversal detected for world '{}'", worldName);
                            continue;
                        }

                        long localTimestamp = config.getLastSyncTimestamp(worldName);

                        if (cloudWorld.lastModified() > localTimestamp) {
                            WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
                            boolean localModified = WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, stats);
                            if (localModified && conflictCallback != null) {
                                SimpleSync.LOGGER.info("[SimpleSync] Conflict detected for world '{}'", worldName);
                                setStatus(SyncStatus.CONFLICT, worldName);

                                CompletableFuture<Boolean> conflictResolution = new CompletableFuture<>();
                                long localMtime = stats.latestModifiedTime();
                                conflictCallback.onConflict(
                                        worldName,
                                        localMtime > 0 ? localMtime : System.currentTimeMillis(),
                                        cloudWorld.lastModified(),
                                        () -> conflictResolution.complete(true),
                                        () -> conflictResolution.complete(false)
                                );

                                boolean useCloud;
                                try {
                                    useCloud = conflictResolution.get(120, TimeUnit.SECONDS);
                                } catch (java.util.concurrent.TimeoutException te) {
                                    SimpleSync.LOGGER.warn("[SimpleSync] Conflict screen timed out (120s) for world '{}'. Defaulting to keep local.", worldName);
                                    if (conflictCancelCallback != null) {
                                        conflictCancelCallback.run();
                                    }
                                    useCloud = false;
                                } catch (InterruptedException ie) {
                                    SimpleSync.LOGGER.warn("[SimpleSync] Sync interrupted during conflict resolution for world '{}'", worldName);
                                    Thread.currentThread().interrupt();
                                    break;
                                }

                                if (!useCloud) {
                                    SimpleSync.LOGGER.info("[SimpleSync] User chose to keep local version of '{}', triggering upload to cloud...", worldName);
                                    uploadWorldAsync(worldName);
                                    continue;
                                }
                            }

                            setStatus(SyncStatus.DOWNLOADING, worldName);

                            Path tempZip = getTempDir().resolve(worldName + ".zip");
                            try {
                                cloud.download(worldName, tempZip);

                                setStatus(SyncStatus.EXTRACTING, worldName);
                                WorldSyncTask.extractWorld(tempZip, worldFolder);

                                updateWorldTracking(config, worldName, worldFolder, cloudWorld.lastModified());

                                downloadCount++;
                                SimpleSync.LOGGER.info("[SimpleSync] Downloaded world: {}", worldName);
                            } finally {
                                deleteQuietly(tempZip);
                            }
                        } else {
                            SimpleSync.LOGGER.info("[SimpleSync] World '{}' is up to date", worldName);
                        }
                    } catch (Exception e) {
                        SimpleSync.LOGGER.error("[SimpleSync] Failed to process world '{}', skipping to next world", cloudWorld.worldName(), e);
                    }
                }

                config.save();

                setStatus(SyncStatus.DONE, "");

            } catch (Exception e) {
                SimpleSync.LOGGER.error("[SimpleSync] Sync from cloud failed", e);
                setStatus(SyncStatus.ERROR, e.getMessage() != null ? e.getMessage() : "Unknown error");
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
                if (worldName == null || worldName.contains("..") || worldName.contains("/") || worldName.contains("\\") || worldName.indexOf('\0') >= 0) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Security violation: invalid world name '{}'", worldName);
                    setStatus(SyncStatus.ERROR, "Invalid world name");
                    return;
                }

                CloudProvider cloud = getProvider();
                if (!cloud.isAuthenticated()) {
                    setStatus(SyncStatus.AUTHENTICATING, "");
                    cloud.authenticate();
                }

                Path savesDir = getSavesDirectory();
                Path worldFolder = savesDir.resolve(worldName).normalize();
                if (!worldFolder.startsWith(savesDir.normalize()) || !Files.isDirectory(worldFolder)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] World folder not found or unsafe: {}", worldFolder);
                    setStatus(SyncStatus.ERROR, worldName);
                    return;
                }

                SyncConfig config = SyncConfig.load();
                WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
                if (!WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, stats)) {
                    SimpleSync.LOGGER.info("[SimpleSync] World '{}' has not changed since last sync, skipping upload", worldName);
                    setStatus(SyncStatus.DONE, "");
                    return;
                }

                setStatus(SyncStatus.COMPRESSING, worldName);
                Path tempZip = getTempDir().resolve(worldName + ".zip");
                try {
                    WorldSyncTask.compressWorld(worldFolder, tempZip);

                    long zipSize = Files.size(tempZip);
                    if (zipSize > 10L * 1024 * 1024 * 1024) {
                        throw new IOException("Compressed world exceeds maximum supported size (10 GB): " + zipSize + " bytes");
                    }

                    setStatus(SyncStatus.UPLOADING, worldName);
                    WorldMetadata uploadedMeta = cloud.upload(worldName, tempZip);

                    long newTimestamp = uploadedMeta != null && uploadedMeta.lastModified() > 0 ? uploadedMeta.lastModified() : System.currentTimeMillis();
                    config.setLastSyncTimestamp(worldName, newTimestamp);
                    config.setLastLocalSize(worldName, stats.size());
                    config.setLastLocalMtime(worldName, stats.latestModifiedTime());
                    config.save();

                    setStatus(SyncStatus.DONE, "");
                    SimpleSync.LOGGER.info("[SimpleSync] Successfully uploaded world: {}", worldName);
                } finally {
                    deleteQuietly(tempZip);
                }

            } catch (Exception e) {
                SimpleSync.LOGGER.error("[SimpleSync] Upload failed for world: {}", worldName, e);
                setStatus(SyncStatus.ERROR, e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        }, executor);
    }

    // ─── Status Management ──────────────────────────────────────────────────

    public StatusSnapshot getStatusSnapshot() {
        return status.get();
    }

    public SyncStatus getStatus() {
        return status.get().status();
    }

    public String getStatusMessage() {
        return status.get().detail();
    }

    public long getStatusTimestamp() {
        return status.get().timestamp();
    }

    public void setStatus(SyncStatus status, String detail) {
        this.status.set(new StatusSnapshot(status, detail != null ? detail : "", System.currentTimeMillis()));
        SimpleSync.LOGGER.debug("[SimpleSync] Status: {} - {}", status, detail);
    }

    public void clearStatus() {
        this.status.set(new StatusSnapshot(SyncStatus.IDLE, "", 0L));
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

    private void updateWorldTracking(SyncConfig config, String worldName, Path worldFolder, long timestamp) {
        config.setLastSyncTimestamp(worldName, timestamp);
        try {
            WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
            config.setLastLocalSize(worldName, stats.size());
            config.setLastLocalMtime(worldName, stats.latestModifiedTime());
        } catch (IOException ignored) {}
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    /**
     * Gracefully shuts down the sync executor, waiting up to 30 seconds for ongoing tasks to finish.
     * Prevents corrupted or aborted syncs when the Minecraft client exits.
     */
    public void shutdownAndAwaitTermination() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                SimpleSync.LOGGER.warn("[SimpleSync] Sync tasks did not finish within 30 seconds. Forcing shutdown...");
                executor.shutdownNow();
            } else {
                SimpleSync.LOGGER.info("[SimpleSync] All sync tasks finished cleanly before JVM exit.");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            try {
                Path tempDir = SyncConfig.getConfigDir().resolve("temp");
                if (Files.isDirectory(tempDir)) {
                    try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir)) {
                        for (Path entry : stream) {
                            deleteQuietly(entry);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}
