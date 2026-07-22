package dev.simplesync.cloud;

import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.*;
import dev.simplesync.util.RetryUtil.RunnableWithException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class CloudSyncManager {

    private static volatile CloudSyncManager instance;

    private final ExecutorService executor;
    private final AtomicReference<StatusSnapshot> status;
    private volatile CloudProvider provider;
    private volatile Path savesDirectory;
    private volatile ConflictCallback conflictCallback;
    private volatile Runnable conflictCancelCallback;
    private volatile AuthPromptCallback authPromptCallback;

    // ─── Callbacks ────────────────────────────────────────────────────────

    public void setConflictCallback(ConflictCallback cb) { this.conflictCallback = cb; }
    public void setConflictCancelCallback(Runnable cb) { this.conflictCancelCallback = cb; }
    public void setAuthPromptCallback(AuthPromptCallback cb) { this.authPromptCallback = cb; }
    public AuthPromptCallback getAuthPromptCallback() { return authPromptCallback; }
    public void setSavesDirectory(Path dir) { this.savesDirectory = dir; }

    // ─── Singleton ────────────────────────────────────────────────────────

    private CloudSyncManager() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SimpleSync-Worker");
            t.setDaemon(true);
            return t;
        });
        this.status = new AtomicReference<>(new StatusSnapshot(SyncStatus.IDLE, "", 0L));
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownAndAwaitTermination, "SimpleSync-Shutdown"));
    }

    public static CloudSyncManager getInstance() {
        if (instance == null) {
            synchronized (CloudSyncManager.class) {
                if (instance == null) instance = new CloudSyncManager();
            }
        }
        return instance;
    }

    public CloudProvider getProvider() {
        if (provider == null) {
            synchronized (this) {
                if (provider == null) provider = new GoogleDriveProvider();
            }
        }
        return provider;
    }

    public ExecutorService getExecutor() { return executor; }

    // ─── Auth (reemplaza ensureAuthenticatedOrThrow) ──────────────────────

    /**
     * @return true if authenticated and ready; false if auth was triggered async (caller should return).
     */
    public boolean ensureAuthenticated(CloudProvider cloud, Runnable onAuthenticated) {
        if (cloud.isAuthenticated()) return true;
        setStatus(SyncStatus.AUTHENTICATING, "");
        if (!cloud.isAuthenticating()) {
            runAsyncSafely("Auth failed", "Authentication failed", () -> {
                cloud.authenticate();
                if (onAuthenticated != null) onAuthenticated.run();
            });
        }
        return false;
    }

    public void ensureAuthenticatedOrThrow(CloudProvider cloud, Runnable onAuthenticated) throws IOException {
        if (!ensureAuthenticated(cloud, onAuthenticated)) {
            throw new IOException("Authentication required");
        }
    }

    // ─── Sync All Worlds ──────────────────────────────────────────────────

    public CompletableFuture<Void> syncAllWorldsFromCloud() {
        return runAsyncSafely("Sync from cloud failed", "Unknown error", () -> {
            CloudProvider cloud = getProvider();
            if (!ensureAuthenticated(cloud, this::syncAllWorldsFromCloud)) return;

            setStatus(SyncStatus.CHECKING, "");
            List<WorldMetadata> cloudWorlds = cloud.listWorlds();
            Path savesDir = getSavesDirectory();
            WorldSyncTask.cleanupOrphanedDirectories(savesDir);
            SyncConfig config = SyncConfig.load();
            int downloadCount = 0;

            for (WorldMetadata cw : cloudWorlds) {
                try {
                    if (processSingleCloudWorld(cloud, savesDir, config, cw)) downloadCount++;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    SimpleSync.LOGGER.error("[SimpleSync] Failed to process '{}', skipping", cw.worldName(), e);
                }
            }

            if (downloadCount > 0) setStatus(SyncStatus.DONE, "");
            else clearStatus();

            if (config.syncSchematics || config.syncMasaConfigs) syncExtraFilesAsync();
        });
    }

    private boolean processSingleCloudWorld(CloudProvider cloud, Path savesDir, SyncConfig config, WorldMetadata cw) throws Exception {
        String worldName = cw.worldName();
        if (!WorldSyncTask.isWorldNameSafe(worldName)) return false;

        Path worldFolder = savesDir.resolve(worldName).normalize();
        if (!worldFolder.startsWith(savesDir.normalize())) return false;
        if (config.ignoredCloudWorlds != null && config.ignoredCloudWorlds.contains(worldName)) return false;

        long localTs = config.getTracking(worldName).lastSyncTimestamp();
        long tolerance = localTs > 0 ? 5000L : 0L;

        if (cw.lastModified() > (localTs + tolerance) || !Files.isDirectory(worldFolder)) {
            if (!Files.isDirectory(worldFolder) && localTs > 0) {
                config.removeTracking(worldName);
                if (config.ignoredCloudWorlds != null) config.ignoredCloudWorlds.add(worldName);
                config.save();
                return false;
            }

            WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
            if (WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, stats)) {
                boolean useCloud = resolveConflict(worldName, stats, cw.lastModified());
                if (!useCloud) { uploadWorldSync(worldName); return false; }
            }

            downloadAndExtract(cloud, worldName, worldFolder, config, cw.lastModified());
            return true;
        } else {
            WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
            if (WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, stats)) {
                uploadWorldSync(worldName);
            }
            return false;
        }
    }

    // ─── Conflict Resolution ──────────────────────────────────────────────

    private boolean resolveConflict(String worldName, WorldSyncTask.WorldStats stats, long cloudModified) throws InterruptedException {
        if (conflictCallback == null) { clearStatus(); return false; }

        setStatus(SyncStatus.CONFLICT, worldName);
        CompletableFuture<Boolean> resolution = new CompletableFuture<>();
        long localModified = stats.latestModifiedTime() > 0 ? stats.latestModifiedTime() : System.currentTimeMillis();

        conflictCallback.onConflict(worldName, localModified, cloudModified,
                () -> resolution.complete(true),
                () -> resolution.complete(false));

        try {
            return resolution.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            triggerConflictCancel();
            return false;
        } catch (InterruptedException e) {
            triggerConflictCancel();
            throw e;
        }
    }

    private void triggerConflictCancel() {
        if (conflictCancelCallback != null) conflictCancelCallback.run();
        clearStatus();
    }

    // ─── Download / Upload ────────────────────────────────────────────────

    private void downloadAndExtract(CloudProvider cloud, String worldName, Path worldFolder, SyncConfig config, long cloudModified) throws IOException {
        setStatus(SyncStatus.DOWNLOADING, worldName);
        Path tempArchive = getTempDir().resolve(worldName + ".tar.zst");
        try {
            cloud.download(worldName, tempArchive);
            setStatus(SyncStatus.EXTRACTING, worldName);
            WorldSyncTask.extractWorld(tempArchive, worldFolder);
            updateTracking(config, worldName, worldFolder, cloudModified);
            config.save();
        } finally {
            deleteQuietly(tempArchive);
        }
    }

    public void uploadWorldSync(String worldName) throws IOException {
        if (!WorldSyncTask.isWorldNameSafe(worldName)) {
            setStatus(SyncStatus.ERROR, "Invalid world name");
            return;
        }

        CloudProvider cloud = getProvider();
        if (!ensureAuthenticated(cloud, () -> uploadWorldAsync(worldName))) return;

        Path savesDir = getSavesDirectory();
        Path worldFolder = savesDir.resolve(worldName).normalize();
        if (!worldFolder.startsWith(savesDir.normalize()) || !Files.isDirectory(worldFolder)) {
            setStatus(SyncStatus.ERROR, worldName);
            return;
        }

        SyncConfig config = SyncConfig.load();
        WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
        if (!WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, stats)) {
            setStatus(SyncStatus.DONE, "");
            return;
        }

        if (stats.size() > 50L * 1024 * 1024 * 1024) {
            throw new IOException("World exceeds 50 GB limit");
        }

        setStatus(SyncStatus.COMPRESSING, worldName);
        Path tempArchive = getTempDir().resolve(worldName + ".tar.zst");
        try {
            WorldSyncTask.compressWorld(worldFolder, tempArchive);
            long archiveSize = Files.size(tempArchive);
            if (archiveSize > 50L * 1024 * 1024 * 1024) throw new IOException("Compressed archive exceeds 50 GB");

            setStatus(SyncStatus.UPLOADING, worldName);
            WorldMetadata uploaded;
            try {
                uploaded = cloud.upload(worldName, tempArchive);
            } catch (IOException uploadEx) {
                // Verify if file actually reached cloud
                WorldMetadata verify = cloud.getWorldMetadata(worldName);
                if (verify == null || verify.sizeBytes() != archiveSize) throw uploadEx;
                uploaded = verify;
            }

            long newTs = uploaded != null && uploaded.lastModified() > 0 ? uploaded.lastModified() : System.currentTimeMillis();
            updateTracking(config, worldName, worldFolder, newTs);
            config.save();
            setStatus(SyncStatus.DONE, "");
        } finally {
            deleteQuietly(tempArchive);
        }
    }

    public CompletableFuture<Void> uploadWorldAsync(String worldName) {
        return runAsyncSafely("Upload failed: " + worldName, "Unknown error", () -> uploadWorldSync(worldName));
    }

    // ─── Delete / Restore ─────────────────────────────────────────────────

    public CompletableFuture<Boolean> deleteWorldFromCloudAsync(String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!WorldSyncTask.isWorldNameSafe(worldName)) { setStatus(SyncStatus.ERROR, "Invalid world name"); return false; }
                CloudProvider cloud = getProvider();
                if (!cloud.isAuthenticated()) { setStatus(SyncStatus.ERROR, "Not authenticated"); return false; }
                cloud.delete(worldName);
                SyncConfig config = SyncConfig.load();
                config.removeTracking(worldName);
                if (config.ignoredCloudWorlds != null) config.ignoredCloudWorlds.remove(worldName);
                config.save();
                return true;
            } catch (Throwable t) {
                SimpleSync.LOGGER.error("[SimpleSync] Delete failed: {}", worldName, t);
                setStatus(SyncStatus.ERROR, t.getMessage() != null ? t.getMessage() : "Delete failed");
                return false;
            }
        }, executor);
    }

    public CompletableFuture<Void> restoreWorldFromCloudAsync(String worldName, Runnable onComplete) {
        return runAsyncSafely("Restore failed: " + worldName, "Unknown error", () -> {
            if (!WorldSyncTask.isWorldNameSafe(worldName)) throw new IllegalArgumentException("Invalid name");
            CloudProvider cloud = getProvider();
            if (!ensureAuthenticated(cloud, () -> restoreWorldFromCloudAsync(worldName, onComplete))) return;

            Path savesDir = getSavesDirectory();
            Path worldFolder = savesDir.resolve(worldName).normalize();
            if (!worldFolder.startsWith(savesDir.normalize())) throw new SecurityException("Path traversal");

            WorldMetadata meta = cloud.getWorldMetadata(worldName);
            if (meta == null) throw new IOException("World not found in cloud");

            setStatus(SyncStatus.DOWNLOADING, worldName);
            Path temp = getTempDir().resolve(worldName + "-restore.tar.zst");
            try {
                cloud.download(worldName, temp);
                setStatus(SyncStatus.EXTRACTING, worldName);
                WorldSyncTask.extractWorld(temp, worldFolder);
                SyncConfig config = SyncConfig.load();
                updateTracking(config, worldName, worldFolder, meta.lastModified());
                config.save();
                setStatus(SyncStatus.DONE, "");
                if (onComplete != null) onComplete.run();
            } finally {
                deleteQuietly(temp);
            }
        });
    }

    // ─── Extra Files Sync (unificado) ─────────────────────────────────────

    public enum ExtraSyncType {
        SCHEMATICS("Schematics"),
        MASA_CONFIGS("Masa Configs");

        private final String label;
        ExtraSyncType(String label) { this.label = label; }
        public String label() { return label; }
    }

    public void syncExtraFilesSync() throws IOException {
        CloudProvider cloud = getProvider();
        if (!ensureAuthenticated(cloud, this::syncExtraFilesAsync)) return;
        SyncConfig config = SyncConfig.load();
        Path gameRoot = getGameRootDir();

        if (config.syncSchematics) {
            try { cloud.syncSchematics(gameRoot); }
            catch (Exception e) { SimpleSync.LOGGER.error("[SimpleSync] Schematics sync failed", e); }
        }
        if (config.syncMasaConfigs) {
            try { cloud.syncMasaConfigs(gameRoot); }
            catch (Exception e) { SimpleSync.LOGGER.error("[SimpleSync] Masa configs sync failed", e); }
        }
    }

    public CompletableFuture<Void> syncExtraFilesAsync() {
        return runAsyncSafely("Extra files sync failed", "Unknown error", this::syncExtraFilesSync);
    }

    /** Unified single-type sync (replaces syncSchematicsSync/Async + syncMasaConfigsSync/Async) */
    public CompletableFuture<Void> syncExtraAsync(ExtraSyncType type) {
        return runAsyncSafely(type.label() + " sync failed", "Unknown error", () -> {
            CloudProvider cloud = getProvider();
            if (!ensureAuthenticated(cloud, () -> syncExtraAsync(type))) return;
            SyncConfig config = SyncConfig.load();
            boolean enabled = type == ExtraSyncType.SCHEMATICS ? config.syncSchematics : config.syncMasaConfigs;
            if (!enabled) return;
            setStatus(SyncStatus.CHECKING, type.label());
            if (type == ExtraSyncType.SCHEMATICS) cloud.syncSchematics(getGameRootDir());
            else cloud.syncMasaConfigs(getGameRootDir());
            setStatus(SyncStatus.DONE, type.label());
        });
    }

    public CompletableFuture<Void> syncSchematicsAsync() {
        return syncExtraAsync(ExtraSyncType.SCHEMATICS);
    }

    public CompletableFuture<Void> syncMasaConfigsAsync() {
        return syncExtraAsync(ExtraSyncType.MASA_CONFIGS);
    }

    public void syncSchematicsSync() throws IOException {
        syncExtraAsync(ExtraSyncType.SCHEMATICS).join();
    }

    public void syncMasaConfigsSync() throws IOException {
        syncExtraAsync(ExtraSyncType.MASA_CONFIGS).join();
    }

    // ─── Status ───────────────────────────────────────────────────────────

    public StatusSnapshot getStatusSnapshot() { return status.get(); }
    public SyncStatus getStatus() { return status.get().status(); }
    public String getStatusMessage() { return status.get().detail(); }
    public long getStatusTimestamp() { return status.get().timestamp(); }

    public void setStatus(SyncStatus s, String detail) {
        this.status.set(new StatusSnapshot(s, detail != null ? detail : "", System.currentTimeMillis()));
    }

    public void clearStatus() {
        this.status.set(new StatusSnapshot(SyncStatus.IDLE, "", 0L));
    }

    // ─── Path Helpers ─────────────────────────────────────────────────────

    public Path getSavesDirectory() {
        if (savesDirectory == null) savesDirectory = Path.of("saves");
        return savesDirectory;
    }

    public Path getGameRootDir() {
        Path parent = getSavesDirectory().getParent();
        return parent != null ? parent : Path.of(".");
    }

    // ─── Private Utilities ────────────────────────────────────────────────

    private Path getTempDir() throws IOException {
        Path tempDir = SyncConfig.getConfigDir().resolve("temp");
        if (Files.isSymbolicLink(tempDir)) throw new IOException("Symlinked temp dir");
        Files.createDirectories(tempDir);
        return tempDir;
    }

    private void updateTracking(SyncConfig config, String worldName, Path worldFolder, long timestamp) {
        if (config.ignoredCloudWorlds != null) config.ignoredCloudWorlds.remove(worldName);
        long size = 0, mtime = 0;
        try {
            WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
            size = stats.size();
            mtime = stats.latestModifiedTime();
        } catch (IOException ignored) {}
        config.setTracking(worldName, new SyncConfig.WorldTrackingInfo(timestamp, size, mtime));
    }

    private void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }

    private CompletableFuture<Void> runAsyncSafely(String errorPrefix, String defaultMsg, RunnableWithException task) {
        return CompletableFuture.runAsync(() -> {
            try { task.run(); }
            catch (Throwable t) {
                SimpleSync.LOGGER.error("[SimpleSync] {}", errorPrefix, t);
                setStatus(SyncStatus.ERROR, t.getMessage() != null ? t.getMessage() : defaultMsg);
            }
        }, executor);
    }

    public void shutdownAndAwaitTermination() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            try {
                Path tempDir = SyncConfig.getConfigDir().resolve("temp");
                if (Files.isDirectory(tempDir)) {
                    try (var stream = Files.newDirectoryStream(tempDir)) {
                        for (Path entry : stream) deleteQuietly(entry);
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}
