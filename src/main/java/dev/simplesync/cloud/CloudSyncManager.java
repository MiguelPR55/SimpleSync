package dev.simplesync.cloud;

import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.ConflictCallback;
import dev.simplesync.sync.StatusSnapshot;
import dev.simplesync.sync.SyncStatus;
import dev.simplesync.sync.WorldMetadata;
import dev.simplesync.sync.WorldSyncTask;
import dev.simplesync.util.RetryUtil.RunnableWithException;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates all cloud synchronization operations.
 * All heavy operations run on a background thread to avoid blocking the game.
 */
public class CloudSyncManager {

    private static volatile CloudSyncManager instance;

    private final ExecutorService executor;
    private final AtomicReference<StatusSnapshot> status;
    private volatile CloudProvider provider;
    private volatile Path savesDirectory;
    private volatile ConflictCallback conflictCallback;
    private volatile Runnable conflictCancelCallback;
    private volatile AuthPromptCallback authPromptCallback;

    public void setConflictCallback(ConflictCallback callback) {
        this.conflictCallback = callback;
    }

    public void setConflictCancelCallback(Runnable callback) {
        this.conflictCancelCallback = callback;
    }

    public void setAuthPromptCallback(AuthPromptCallback callback) {
        this.authPromptCallback = callback;
    }

    public AuthPromptCallback getAuthPromptCallback() {
        return authPromptCallback;
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

    public static CloudSyncManager getInstance() {
        if (instance == null) {
            synchronized (CloudSyncManager.class) {
                if (instance == null) {
                    instance = new CloudSyncManager();
                }
            }
        }
        return instance;
    }

    public CloudProvider getProvider() {
        if (provider == null) {
            synchronized (this) {
                if (provider == null) {
                    provider = new GoogleDriveProvider();
                }
            }
        }
        return provider;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public void ensureAuthenticatedOrThrow(CloudProvider cloud, Runnable onAuthenticated) throws IOException {
        if (!cloud.isAuthenticated()) {
            setStatus(SyncStatus.AUTHENTICATING, "");
            if (cloud.isAuthenticating()) {
                throw new IOException("Authentication pending: please complete device authorization in browser.");
            }
            runAsyncSafely("Background authentication failed", "Authentication failed", () -> {
                cloud.authenticate();
                if (onAuthenticated != null) {
                    onAuthenticated.run();
                }
            });
            throw new IOException("Authentication required: please complete device authorization in browser.");
        }
    }

    /**
     * Downloads all worlds from the cloud that are newer than local copies.
     * Called when the game starts (from TitleScreen).
     */
    public CompletableFuture<Void> syncAllWorldsFromCloud() {
        return runAsyncSafely("Sync from cloud failed", "Unknown error", () -> {
            CloudProvider cloud = getProvider();
            ensureAuthenticatedOrThrow(cloud, this::syncAllWorldsFromCloud);

            setStatus(SyncStatus.CHECKING, "");

            List<WorldMetadata> cloudWorlds = cloud.listWorlds();
            Path savesDirectoryPath = getSavesDirectory();
            WorldSyncTask.cleanupOrphanedDirectories(savesDirectoryPath);
            SyncConfig config = SyncConfig.load();
            int downloadCount = 0;

            for (WorldMetadata cloudWorld : cloudWorlds) {
                try {
                    boolean downloaded = processSingleCloudWorld(cloud, savesDirectoryPath, config, cloudWorld);
                    if (downloaded) {
                        downloadCount++;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    SimpleSync.LOGGER.error("[SimpleSync] Failed to process world '{}', skipping to next world", cloudWorld.worldName(), e);
                }
            }

            if (downloadCount > 0) {
                setStatus(SyncStatus.DONE, "");
            } else {
                clearStatus();
            }
        });
    }

    private boolean processSingleCloudWorld(CloudProvider cloudProvider, Path savesDir, SyncConfig config, WorldMetadata cloudWorld) throws Exception {
        String worldName = cloudWorld.worldName();
        if (!WorldSyncTask.isWorldNameSafe(worldName)) {
            SimpleSync.LOGGER.warn("[SimpleSync] Security violation: ignoring cloud world with unsafe name '{}'", worldName);
            return false;
        }
        Path worldFolder = savesDir.resolve(worldName).normalize();
        if (!worldFolder.startsWith(savesDir.normalize())) {
            SimpleSync.LOGGER.warn("[SimpleSync] Security violation: path traversal detected for world '{}'", worldName);
            return false;
        }
        if (config.ignoredCloudWorlds != null && config.ignoredCloudWorlds.contains(worldName)) {
            SimpleSync.LOGGER.info("[SimpleSync] Skipping cloud world '{}' because it is marked as ignored/archived locally.", worldName);
            return false;
        }

        long localTimestamp = config.getTracking(worldName).lastSyncTimestamp();
        long tolerance = localTimestamp > 0 ? 5000L : 0L;

        if (cloudWorld.lastModified() > (localTimestamp + tolerance) || !Files.isDirectory(worldFolder)) {
            if (!Files.isDirectory(worldFolder) && localTimestamp > 0) {
                SimpleSync.LOGGER.info("[SimpleSync] World '{}' was deleted outside Minecraft. Adding to ignored/archived list to protect cloud backup...", worldName);
                config.removeTracking(worldName);
                if (config.ignoredCloudWorlds != null) {
                    config.ignoredCloudWorlds.add(worldName);
                }
                config.save();
                return false;
            }

            WorldSyncTask.WorldStats worldStats = WorldSyncTask.getWorldStats(worldFolder);
            boolean isLocalModified = WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, worldStats);
            if (isLocalModified) {
                boolean shouldUseCloud = resolveWorldConflict(worldName, worldStats, cloudWorld.lastModified());
                if (!shouldUseCloud) {
                    SimpleSync.LOGGER.info("[SimpleSync] User chose local version of '{}', triggering upload to cloud...", worldName);
                    uploadWorldSync(worldName);
                    return false;
                }
            }

            downloadAndExtractCloudWorld(cloudProvider, worldName, worldFolder, config, cloudWorld.lastModified());
            return true;
        } else {
            WorldSyncTask.WorldStats worldStats = WorldSyncTask.getWorldStats(worldFolder);
            if (WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, worldStats)) {
                SimpleSync.LOGGER.info("[SimpleSync] Local world '{}' has modifications not present in cloud. Triggering upload...", worldName);
                uploadWorldSync(worldName);
            } else {
                SimpleSync.LOGGER.info("[SimpleSync] World '{}' is up to date", worldName);
            }
            return false;
        }
    }

    private boolean resolveWorldConflict(String worldName, WorldSyncTask.WorldStats worldStats, long cloudModifiedTime) throws InterruptedException {
        if (conflictCallback == null) {
            SimpleSync.LOGGER.warn("[SimpleSync] Conflict detected for world '{}', but no conflict callback is registered! Skipping sync for safety.", worldName);
            clearStatus();
            return false;
        }

        SimpleSync.LOGGER.info("[SimpleSync] Conflict detected for world '{}'", worldName);
        setStatus(SyncStatus.CONFLICT, worldName);

        CompletableFuture<Boolean> conflictResolution = new CompletableFuture<>();
        long localModifiedTime = worldStats.latestModifiedTime();
        conflictCallback.onConflict(
                worldName,
                localModifiedTime > 0 ? localModifiedTime : System.currentTimeMillis(),
                cloudModifiedTime,
                () -> conflictResolution.complete(true),
                () -> conflictResolution.complete(false)
        );

        try {
            return conflictResolution.get(120, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeoutException) {
            SimpleSync.LOGGER.warn("[SimpleSync] Conflict screen timed out (120s) for world '{}'. Cancelling sync.", worldName);
            triggerConflictCancel();
            return false;
        } catch (ExecutionException executionException) {
            SimpleSync.LOGGER.error("[SimpleSync] Error during conflict resolution for world '{}'. Skipping sync.", worldName, executionException.getCause());
            triggerConflictCancel();
            return false;
        } catch (InterruptedException interruptedException) {
            SimpleSync.LOGGER.warn("[SimpleSync] Sync interrupted during conflict resolution for world '{}'", worldName);
            triggerConflictCancel();
            throw interruptedException;
        }
    }

    private void triggerConflictCancel() {
        if (conflictCancelCallback != null) {
            conflictCancelCallback.run();
        }
        clearStatus();
    }

    private void downloadAndExtractCloudWorld(CloudProvider cloudProvider, String worldName, Path worldFolder, SyncConfig config, long cloudModifiedTime) throws IOException {
        setStatus(SyncStatus.DOWNLOADING, worldName);

        Path tempArchive = getTempDir().resolve(worldName + ".tar.zst");
        try {
            cloudProvider.download(worldName, tempArchive);

            setStatus(SyncStatus.EXTRACTING, worldName);
            WorldSyncTask.extractWorld(tempArchive, worldFolder);

            updateWorldTracking(config, worldName, worldFolder, cloudModifiedTime);
            config.save();

            SimpleSync.LOGGER.info("[SimpleSync] Successfully downloaded world: {}", worldName);
        } finally {
            deleteQuietly(tempArchive);
        }
    }

    /**
     * Uploads a specific world to the cloud synchronously.
     * Must be called from a background thread (worker thread).
     */
    public void uploadWorldSync(String worldName) throws IOException {
        if (!WorldSyncTask.isWorldNameSafe(worldName)) {
            SimpleSync.LOGGER.warn("[SimpleSync] Security violation: invalid world name '{}'", worldName);
            setStatus(SyncStatus.ERROR, "Invalid world name");
            return;
        }

        CloudProvider cloud = getProvider();
        ensureAuthenticatedOrThrow(cloud, () -> uploadWorldAsync(worldName));

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

        if (stats.size() > 50L * 1024 * 1024 * 1024) {
            throw new IOException("World uncompressed size exceeds maximum supported limit (50 GB): " + stats.size() + " bytes");
        }

        setStatus(SyncStatus.COMPRESSING, worldName);
        Path tempArchive = getTempDir().resolve(worldName + ".tar.zst");
        try {
            WorldSyncTask.compressWorld(worldFolder, tempArchive);

            long archiveSize = Files.size(tempArchive);
            if (archiveSize > 50L * 1024 * 1024 * 1024) {
                throw new IOException("Compressed world exceeds maximum supported size (50 GB): " + archiveSize + " bytes");
            }

            setStatus(SyncStatus.UPLOADING, worldName);
            WorldMetadata uploadedMeta;
            try {
                uploadedMeta = cloud.upload(worldName, tempArchive);
            } catch (IOException uploadEx) {
                SimpleSync.LOGGER.warn("[SimpleSync] Upload failed/timed out. Verifying if file reached cloud: {}", uploadEx.getMessage());
                try {
                    uploadedMeta = cloud.getWorldMetadata(worldName);
                    if (uploadedMeta == null || uploadedMeta.sizeBytes() != archiveSize) {
                        throw uploadEx;
                    }
                    SimpleSync.LOGGER.info("[SimpleSync] Verified that the remote file size matches the local archive. Considering upload successful.");
                } catch (Exception verifyEx) {
                    SimpleSync.LOGGER.error("[SimpleSync] Failed to verify remote file status", verifyEx);
                    throw uploadEx;
                }
            }

            long newTimestamp = uploadedMeta != null && uploadedMeta.lastModified() > 0 ? uploadedMeta.lastModified() : System.currentTimeMillis();
            updateWorldTracking(config, worldName, worldFolder, newTimestamp);
            config.save();

            setStatus(SyncStatus.DONE, "");
            SimpleSync.LOGGER.info("[SimpleSync] Successfully uploaded world: {}", worldName);
        } finally {
            deleteQuietly(tempArchive);
        }
    }

    public CompletableFuture<Void> uploadWorldAsync(String worldName) {
        return runAsyncSafely("Upload failed for world: " + worldName, "Unknown error", () -> {
            uploadWorldSync(worldName);
        });
    }

    /**
     * Deletes a world from the cloud asynchronously on the executor thread.
     */
    public CompletableFuture<Boolean> deleteWorldFromCloudAsync(String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!WorldSyncTask.isWorldNameSafe(worldName)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Security violation: cannot delete world with unsafe name '{}'", worldName);
                    setStatus(SyncStatus.ERROR, "Nombre de mundo no válido");
                    return false;
                }
                CloudProvider cloud = getProvider();
                if (cloud == null || !cloud.isAuthenticated()) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Cannot delete remote world '{}': Not authenticated", worldName);
                    setStatus(SyncStatus.ERROR, "No autenticado en Google Drive para borrar mundo en la nube.");
                    return false;
                }
                SimpleSync.LOGGER.info("[SimpleSync] Deleting world '{}' from cloud...", worldName);
                cloud.delete(worldName);

                SyncConfig config = SyncConfig.load();
                config.removeTracking(worldName);
                if (config.ignoredCloudWorlds != null) {
                    config.ignoredCloudWorlds.remove(worldName);
                }
                config.save();
                SimpleSync.LOGGER.info("[SimpleSync] Successfully deleted remote world '{}'", worldName);
                return true;
            } catch (Throwable t) {
                SimpleSync.LOGGER.error("[SimpleSync] Failed to delete remote world: {}", worldName, t);
                setStatus(SyncStatus.ERROR, t.getMessage() != null ? t.getMessage() : "Failed to delete remote world");
                return false;
            }
        }, executor);
    }

    public CompletableFuture<Void> restoreWorldFromCloudAsync(String worldName, Runnable onComplete) {
        return runAsyncSafely("Failed to restore world: " + worldName, "Unknown error", () -> {
            if (!WorldSyncTask.isWorldNameSafe(worldName)) {
                throw new IllegalArgumentException("Invalid world name: " + worldName);
            }
            CloudProvider cloud = getProvider();
            ensureAuthenticatedOrThrow(cloud, () -> restoreWorldFromCloudAsync(worldName, onComplete));

            Path savesDir = getSavesDirectory();
            Path worldFolder = savesDir.resolve(worldName).normalize();
            if (!worldFolder.startsWith(savesDir.normalize())) {
                throw new SecurityException("Path traversal detected: " + worldName);
            }

            WorldMetadata meta = cloud.getWorldMetadata(worldName);
            if (meta == null) {
                throw new IOException("Remote world not found on Google Drive: " + worldName);
            }

            setStatus(SyncStatus.DOWNLOADING, worldName);
            Path tempArchive = getTempDir().resolve(worldName + "-restore.tar.zst");
            try {
                cloud.download(worldName, tempArchive);
                setStatus(SyncStatus.EXTRACTING, worldName);
                WorldSyncTask.extractWorld(tempArchive, worldFolder);

                SyncConfig config = SyncConfig.load();
                updateWorldTracking(config, worldName, worldFolder, meta.lastModified());
                config.save();

                setStatus(SyncStatus.DONE, "");
                SimpleSync.LOGGER.info("[SimpleSync] Successfully restored world from cloud: {}", worldName);
                if (onComplete != null) {
                    onComplete.run();
                }
            } finally {
                deleteQuietly(tempArchive);
            }
        });
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

    public Path getSavesDirectory() {
        if (savesDirectory == null) {
            savesDirectory = Path.of("saves");
        }
        return savesDirectory;
    }

    private Path getTempDir() throws IOException {
        Path tempDir = SyncConfig.getConfigDir().resolve("temp");
        if (Files.isSymbolicLink(tempDir)) {
            throw new IOException("Refusing to use symlinked temp directory: " + tempDir);
        }
        Files.createDirectories(tempDir);
        return tempDir;
    }

    private void updateWorldTracking(SyncConfig config, String worldName, Path worldFolder, long timestamp) {
        if (config.ignoredCloudWorlds != null) {
            config.ignoredCloudWorlds.remove(worldName);
        }
        long size = 0L;
        long mtime = 0L;
        try {
            WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
            size = stats.size();
            mtime = stats.latestModifiedTime();
        } catch (IOException ignored) {}
        config.setTracking(worldName, new SyncConfig.WorldTrackingInfo(timestamp, size, mtime));
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    private CompletableFuture<Void> runAsyncSafely(String errorLogPrefix, String defaultErrorMessage, RunnableWithException task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                SimpleSync.LOGGER.error("[SimpleSync] {}", errorLogPrefix, t);
                setStatus(SyncStatus.ERROR, t.getMessage() != null ? t.getMessage() : defaultErrorMessage);
            }
        }, executor);
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
