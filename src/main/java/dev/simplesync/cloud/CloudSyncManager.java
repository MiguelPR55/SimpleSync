package dev.simplesync.cloud;

import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.*;
import dev.simplesync.util.RetryUtil.RunnableWithException;
import dev.simplesync.util.SyncLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class CloudSyncManager {

    private static volatile CloudSyncManager instance;

    private final ExecutorService executor;
    private final AtomicReference<StatusSnapshot> status;
    private final java.util.concurrent.atomic.AtomicBoolean isSyncingAll = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final List<Runnable> pendingAuthCallbacks = new CopyOnWriteArrayList<>();
    private final Set<String> synchronizedWorlds = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingSyncWorlds = ConcurrentHashMap.newKeySet();
    private volatile String currentSyncingWorld = null;
    private volatile boolean initialSyncCompleted = false;
    private volatile CloudProvider provider;
    private volatile Path savesDirectory;
    private volatile ConflictCallback conflictCallback;
    private volatile Runnable conflictCancelCallback;
    private volatile AuthPromptCallback authPromptCallback;
    private volatile java.util.function.Consumer<String> worldSyncedCallback;
    private volatile Runnable batchSyncCompleteCallback;

    // ─── Callbacks ────────────────────────────────────────────────────────

    public void setConflictCallback(ConflictCallback cb) { this.conflictCallback = cb; }
    public void setConflictCancelCallback(Runnable cb) { this.conflictCancelCallback = cb; }
    public void setAuthPromptCallback(AuthPromptCallback cb) { this.authPromptCallback = cb; }
    public AuthPromptCallback getAuthPromptCallback() { return authPromptCallback; }
    public void setWorldSyncedCallback(java.util.function.Consumer<String> cb) { this.worldSyncedCallback = cb; }
    public void setBatchSyncCompleteCallback(Runnable cb) { this.batchSyncCompleteCallback = cb; }
    public boolean isSyncingAll() { return isSyncingAll.get(); }
    public void setSavesDirectory(Path dir) { this.savesDirectory = dir; }

    // ─── Singleton ────────────────────────────────────────────────────────

    private CloudSyncManager() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SimpleSync-Worker");
            t.setDaemon(true);
            return t;
        });
        this.status = new AtomicReference<>(new StatusSnapshot(SyncStatus.IDLE, "", 0L));

        // Do NOT register standalone-spawning shutdown hook when running inside the standalone uploader itself!
        if (!"true".equals(System.getProperty("simplesync.standalone"))) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownAndAwaitTermination, "SimpleSync-Shutdown"));
        }
    }

    public static CloudSyncManager getInstance() {
        if (instance == null) {
            synchronized (CloudSyncManager.class) {
                if (instance == null) instance = new CloudSyncManager();
            }
        }
        return instance;
    }

    public void resetStateForTests() {
        this.synchronizedWorlds.clear();
        this.pendingSyncWorlds.clear();
        this.currentSyncingWorld = null;
        this.initialSyncCompleted = false;
        this.isSyncingAll.set(false);
        this.status.set(new StatusSnapshot(SyncStatus.IDLE, "", 0L));
    }

    public CloudProvider getProvider() {
        if (provider == null) {
            synchronized (this) {
                if (provider == null) {
                    SyncConfig config = SyncConfig.load();
                    provider = CloudProviderFactory.create(config.cloudProvider);
                }
            }
        }
        return provider;
    }

    public ExecutorService getExecutor() { return executor; }

    // ─── Auth ─────────────────────────────────────────────────────────────

    /**
     * @return true if authenticated and ready; false if auth was triggered async (caller should return).
     */
    public boolean ensureAuthenticated(CloudProvider cloud, Runnable onAuthenticated) {
        if (cloud.isAuthenticated()) return true;
        setStatus(SyncStatus.AUTHENTICATING, "");
        if (onAuthenticated != null) {
            pendingAuthCallbacks.add(onAuthenticated);
        }
        if (!cloud.isAuthenticating()) {
            runAsyncSafely("Auth failed", "Authentication failed", () -> {
                try {
                    cloud.authenticate();
                    List<Runnable> callbacks = new ArrayList<>(pendingAuthCallbacks);
                    pendingAuthCallbacks.clear();
                    for (Runnable cb : callbacks) {
                        try { cb.run(); } catch (Exception e) { SyncLogger.error("[SimpleSync] Auth callback error", e); }
                    }
                } catch (Throwable t) {
                    pendingAuthCallbacks.clear();
                    throw t;
                }
            });
        }
        return false;
    }

    public void ensureAuthenticatedOrThrow(CloudProvider cloud, Runnable onAuthenticated) throws IOException {
        if (!ensureAuthenticated(cloud, onAuthenticated)) {
            throw new IOException("Authentication required");
        }
    }

    public boolean isWorldSynchronized(String worldName) {
        if (worldName == null) return false;
        SyncConfig config = SyncConfig.load();
        if (!config.autoSyncOnStart && initialSyncCompleted) {
            return true;
        }
        if (isWorldBusy(worldName)) {
            return false;
        }
        return synchronizedWorlds.contains(worldName);
    }

    public boolean isWorldBusy(String worldName) {
        if (worldName == null) return false;
        String current = currentSyncingWorld;
        if (current != null && current.equals(worldName)) return true;
        StatusSnapshot snap = getStatusSnapshot();
        return snap.status().isBusy() && worldName.equals(extractBaseWorldName(snap.detail()));
    }

    public boolean isInitialSyncCompleted() {
        return initialSyncCompleted;
    }

    public void markInitialSyncCompleted() {
        this.initialSyncCompleted = true;
        Path savesDir = getSavesDirectory();
        if (Files.isDirectory(savesDir)) {
            try (var stream = Files.list(savesDir)) {
                stream.filter(Files::isDirectory)
                      .map(p -> p.getFileName().toString())
                      .filter(WorldSyncTask::isWorldNameSafe)
                      .forEach(synchronizedWorlds::add);
            } catch (IOException ignored) {}
        }
        pendingSyncWorlds.clear();
        Runnable completeCb = this.batchSyncCompleteCallback;
        if (completeCb != null) {
            try { completeCb.run(); } catch (Exception e) { SyncLogger.error("[SimpleSync] Batch sync complete callback error", e); }
        }
    }

    public void markWorldSynchronized(String worldName) {
        if (worldName != null) {
            synchronizedWorlds.add(worldName);
            pendingSyncWorlds.remove(worldName);
            if (worldName.equals(currentSyncingWorld)) {
                currentSyncingWorld = null;
            }
            java.util.function.Consumer<String> cb = this.worldSyncedCallback;
            if (cb != null) {
                try { cb.accept(worldName); } catch (Exception e) { SyncLogger.error("[SimpleSync] World synced callback error", e); }
            }
        }
    }

    public void markWorldUnsynchronized(String worldName) {
        if (worldName != null) {
            synchronizedWorlds.remove(worldName);
            pendingSyncWorlds.add(worldName);
        }
    }

    public String getCurrentSyncingWorld() {
        return currentSyncingWorld;
    }

    public Set<String> getSynchronizedWorlds() {
        return Collections.unmodifiableSet(synchronizedWorlds);
    }

    public Set<String> getPendingSyncWorlds() {
        return Collections.unmodifiableSet(pendingSyncWorlds);
    }

    // ─── Sync All Worlds ──────────────────────────────────────────────────

    public CompletableFuture<Void> syncAllWorldsFromCloud() {
        if (!isSyncingAll.compareAndSet(false, true)) {
            SyncLogger.info("[SimpleSync] Batch cloud sync is already running. Skipping duplicate trigger.");
            return CompletableFuture.completedFuture(null);
        }
        try {
            return runAsyncSafely("Sync from cloud failed", "Unknown error", () -> {
                try {
                    CloudProvider cloud = getProvider();
                    if (!ensureAuthenticated(cloud, this::syncAllWorldsFromCloud)) return;

                try {
                    setStatus(SyncStatus.CHECKING, "");
                    Path savesDir = getSavesDirectory();
                    WorldSyncTask.cleanupOrphanedDirectories(savesDir);

                    // Populate pendingSyncWorlds initially with existing local saves
                    if (Files.isDirectory(savesDir)) {
                        try (var stream = Files.list(savesDir)) {
                            stream.filter(Files::isDirectory)
                                  .map(p -> p.getFileName().toString())
                                  .filter(WorldSyncTask::isWorldNameSafe)
                                  .forEach(pendingSyncWorlds::add);
                        } catch (IOException e) {
                            SyncLogger.warn("[SimpleSync] Failed to list saves directory", e);
                        }
                    }

                    List<WorldMetadata> cloudWorlds = cloud.listWorlds();
                    if (cloudWorlds != null) {
                        for (WorldMetadata meta : cloudWorlds) {
                            if (WorldSyncTask.isWorldNameSafe(meta.worldName())) {
                                pendingSyncWorlds.add(meta.worldName());
                            }
                        }
                    }

                    SyncConfig config = SyncConfig.load();
                    int downloadCount = 0;

                    if (cloudWorlds != null) {
                        for (WorldMetadata cw : cloudWorlds) {
                            String wName = cw.worldName();
                            if (dev.simplesync.SimpleSync.isWorldRunning(wName)) {
                                SyncLogger.info("[SimpleSync] World '{}' is currently running in-game. Skipping background sync.", wName);
                                continue;
                            }
                            try {
                                currentSyncingWorld = wName;
                                if (processSingleCloudWorld(cloud, savesDir, config, cw, true)) {
                                    downloadCount++;
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (Exception e) {
                                SyncLogger.error("[SimpleSync] Failed to process '{}', skipping", wName, e);
                            } finally {
                                markWorldSynchronized(wName);
                                if (wName.equals(currentSyncingWorld)) {
                                    currentSyncingWorld = null;
                                }
                            }
                        }
                    }

                    // Check remaining local-only worlds
                    List<String> remainingLocal = new ArrayList<>(pendingSyncWorlds);
                    for (String localName : remainingLocal) {
                        if (dev.simplesync.SimpleSync.isWorldRunning(localName)) {
                            continue;
                        }
                        Path localFolder = savesDir.resolve(localName);
                        if (Files.isDirectory(localFolder)) {
                            try {
                                WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(localFolder);
                                if (WorldSyncTask.isLocalWorldModified(localFolder, config, localName, stats)) {
                                    currentSyncingWorld = localName;
                                    uploadWorldSync(localName, true);
                                }
                            } catch (Exception e) {
                                SyncLogger.error("[SimpleSync] Failed to process local-only world '{}'", localName, e);
                            } finally {
                                markWorldSynchronized(localName);
                                if (localName.equals(currentSyncingWorld)) {
                                    currentSyncingWorld = null;
                                }
                            }
                        } else {
                            pendingSyncWorlds.remove(localName);
                        }
                    }

                    if (config.syncSchematics || config.syncMasaConfigs) {
                        syncExtraFilesSync(false);
                    }

                    initialSyncCompleted = true;

                    if (downloadCount > 0) setStatus(SyncStatus.DONE, "");
                    else clearStatus();

                    Runnable completeCb = this.batchSyncCompleteCallback;
                    if (completeCb != null) {
                        try { completeCb.run(); } catch (Exception e) { SyncLogger.error("[SimpleSync] Batch sync complete callback error", e); }
                    }
                } catch (Throwable t) {
                    markInitialSyncCompleted();
                    throw t;
                }
            } finally {
                isSyncingAll.set(false);
            }
        });
        } catch (Throwable t) {
            isSyncingAll.set(false);
            throw t;
        }
    }

    private boolean processSingleCloudWorld(CloudProvider cloud, Path savesDir, SyncConfig config, WorldMetadata cw, boolean isBatch) throws Exception {
        String worldName = cw.worldName();
        if (!WorldSyncTask.isWorldNameSafe(worldName)) return false;

        Path worldFolder = savesDir.resolve(worldName).normalize();
        if (!worldFolder.startsWith(savesDir.normalize())) return false;
        if (config.ignoredCloudWorlds != null && config.ignoredCloudWorlds.contains(worldName)) return false;

        boolean isLocalDir = Files.isDirectory(worldFolder);
        long localTs = config.getTracking(worldName).lastSyncTimestamp();
        long tolerance = localTs > 0 ? 5000L : 0L;

        if (!isLocalDir && localTs > 0) {
            config.removeTracking(worldName);
            if (config.ignoredCloudWorlds != null) config.ignoredCloudWorlds.add(worldName);
            config.save();
            return false;
        }

        WorldSyncTask.WorldStats stats = isLocalDir ? WorldSyncTask.getWorldStats(worldFolder) : new WorldSyncTask.WorldStats(0, 0);
        boolean localModified = isLocalDir && WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, stats);

        if (cw.lastModified() > (localTs + tolerance) || !isLocalDir) {
            if (localModified) {
                boolean useCloud = resolveConflict(worldName, stats, cw.lastModified());
                if (!useCloud) {
                    uploadWorldSync(worldName, isBatch);
                    return false;
                }
            }

            downloadAndExtract(cloud, worldName, worldFolder, config, cw.lastModified());
            return true;
        } else if (localModified) {
            uploadWorldSync(worldName, isBatch);
        }
        return false;
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
        uploadWorldSync(worldName, false);
    }

    public void uploadWorldSync(String worldName, boolean isBatch) throws IOException {
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

        currentSyncingWorld = worldName;
        synchronizedWorlds.remove(worldName);
        pendingSyncWorlds.add(worldName);

        SyncConfig config = SyncConfig.load();
        WorldSyncTask.WorldStats stats = WorldSyncTask.getWorldStats(worldFolder);
        if (!WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName, stats)) {
            markWorldSynchronized(worldName);
            if (!isBatch) setStatus(SyncStatus.DONE, "");
            return;
        }

        if (stats.size() > 50L * 1024 * 1024 * 1024) {
            throw new IOException("World exceeds 50 GB limit");
        }

        try {
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
                markWorldSynchronized(worldName);
                if (!isBatch) setStatus(SyncStatus.DONE, "");
            } finally {
                deleteQuietly(tempArchive);
                if (worldName.equals(currentSyncingWorld)) {
                    currentSyncingWorld = null;
                }
            }
        } catch (Throwable t) {
            markWorldSynchronized(worldName);
            throw t;
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
                synchronizedWorlds.remove(worldName);
                pendingSyncWorlds.remove(worldName);
                SyncConfig config = SyncConfig.load();
                config.removeTracking(worldName);
                if (config.ignoredCloudWorlds != null) config.ignoredCloudWorlds.remove(worldName);
                config.save();
                return true;
            } catch (Throwable t) {
                SyncLogger.error("[SimpleSync] Delete failed: {}", worldName, t);
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

            currentSyncingWorld = worldName;
            synchronizedWorlds.remove(worldName);
            pendingSyncWorlds.add(worldName);

            setStatus(SyncStatus.DOWNLOADING, worldName);
            Path temp = getTempDir().resolve(worldName + "-restore.tar.zst");
            try {
                cloud.download(worldName, temp);
                setStatus(SyncStatus.EXTRACTING, worldName);
                WorldSyncTask.extractWorld(temp, worldFolder);
                SyncConfig config = SyncConfig.load();
                updateTracking(config, worldName, worldFolder, meta.lastModified());
                config.save();
                markWorldSynchronized(worldName);
                setStatus(SyncStatus.DONE, "");
                if (onComplete != null) onComplete.run();
            } finally {
                deleteQuietly(temp);
                if (worldName.equals(currentSyncingWorld)) {
                    currentSyncingWorld = null;
                }
            }
        });
    }

    // ─── Extra Files Sync ─────────────────────────────────────────────────

    public enum ExtraSyncType {
        SCHEMATICS("Schematics"),
        MASA_CONFIGS("Masa Configs");

        private final String label;
        ExtraSyncType(String label) { this.label = label; }
        public String label() { return label; }
    }

    public void syncExtraFilesSync() throws IOException {
        syncExtraFilesSync(true);
    }

    public void syncExtraFilesSync(boolean clearStatusWhenDone) throws IOException {
        CloudProvider cloud = getProvider();
        if (!ensureAuthenticated(cloud, this::syncExtraFilesAsync)) return;
        SyncConfig config = SyncConfig.load();
        Path gameRoot = getGameRootDir();

        if (config.syncSchematics) {
            try {
                setStatus(SyncStatus.CHECKING, "Schematics");
                cloud.syncSchematics(gameRoot);
            } catch (Exception e) {
                SyncLogger.error("[SimpleSync] Schematics sync failed", e);
            }
        }
        if (config.syncMasaConfigs) {
            try {
                setStatus(SyncStatus.CHECKING, "Masa Configs");
                cloud.syncMasaConfigs(gameRoot);
            } catch (Exception e) {
                SyncLogger.error("[SimpleSync] Masa configs sync failed", e);
            }
        }
        if (clearStatusWhenDone) {
            clearStatus();
        }
    }

    public CompletableFuture<Void> syncExtraFilesAsync() {
        return runAsyncSafely("Extra files sync failed", "Unknown error", this::syncExtraFilesSync);
    }

    /** Unified single-type sync */
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
        joinUnwrapped(syncExtraAsync(ExtraSyncType.SCHEMATICS));
    }

    public void syncMasaConfigsSync() throws IOException {
        joinUnwrapped(syncExtraAsync(ExtraSyncType.MASA_CONFIGS));
    }

    private static void joinUnwrapped(CompletableFuture<Void> future) throws IOException {
        try {
            future.join();
        } catch (CompletionException ce) {
            if (ce.getCause() instanceof IOException ioe) throw ioe;
            if (ce.getCause() instanceof RuntimeException re) throw re;
            throw new IOException(ce.getCause() != null ? ce.getCause() : ce);
        }
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
                if (getStatus() == SyncStatus.AUTHENTICATING) {
                    SyncLogger.info("[SimpleSync] {} - authentication in progress, keeping AUTHENTICATING status.", errorPrefix);
                    return;
                }
                Throwable cause = t;
                while (cause.getCause() != null && cause != cause.getCause()) {
                    if (cause instanceof DeviceCodeAuthenticator.AuthCancelledException) break;
                    cause = cause.getCause();
                }
                if (cause instanceof DeviceCodeAuthenticator.AuthCancelledException) {
                    SyncLogger.info("[SimpleSync] Authentication cancelled by user");
                    clearStatus();
                } else {
                    SyncLogger.error("[SimpleSync] {}", errorPrefix, t);
                    setStatus(SyncStatus.ERROR, t.getMessage() != null ? t.getMessage() : defaultMsg);
                }
            }
        }, executor);
    }

    public boolean spawnStandaloneUploader(String worldName, Path worldFolder, Path archivePath) {
        try {
            String javaBin = ProcessHandle.current().info().command()
                    .filter(cmd -> !cmd.isBlank())
                    .orElseGet(() -> {
                        String javaHome = System.getProperty("java.home");
                        if (javaHome != null && !javaHome.isBlank()) {
                            Path binJava = Path.of(javaHome, "bin", System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java");
                            if (Files.isExecutable(binJava)) {
                                return binJava.toAbsolutePath().toString();
                            }
                        }
                        return "java";
                    });
            Path configDir = SyncConfig.getConfigDir();

            var modContainer = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("simplesync");
            if (modContainer.isEmpty()) return false;
            String classpath = modContainer.get().getOrigin().getPaths().stream()
                    .map(p -> p.toAbsolutePath().toString())
                    .collect(Collectors.joining(File.pathSeparator));

            List<String> command = new ArrayList<>();
            command.add(javaBin);
            command.add("-Dsimplesync.standalone=true");
            command.add("-cp");
            command.add(classpath);
            command.add("dev.simplesync.cloud.StandaloneUploader");
            command.add("--world");
            command.add(worldName);
            if (worldFolder != null && Files.isDirectory(worldFolder)) {
                command.add("--worldDir");
                command.add(worldFolder.toAbsolutePath().toString());
            }
            if (archivePath != null) {
                command.add("--archive");
                command.add(archivePath.toAbsolutePath().toString());
            }
            command.add("--config");
            command.add(configDir.toAbsolutePath().toString());
            Path gameRoot = getGameRootDir();
            if (gameRoot != null && Files.isDirectory(gameRoot)) {
                command.add("--gameDir");
                command.add(gameRoot.toAbsolutePath().toString());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            Path logFile = configDir.resolve("uploader.log");
            pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.to(logFile.toFile()));
            pb.start();
            SyncLogger.info("[SimpleSync] Spawned detached background uploader process for: {}", worldName);
            return true;
        } catch (Exception e) {
            SyncLogger.error("[SimpleSync] Failed to spawn standalone background uploader", e);
            return false;
        }
    }

    public void shutdownAndAwaitTermination() {
        StatusSnapshot snapshot = getStatusSnapshot();
        SyncStatus status = snapshot.status();

        if (status == SyncStatus.UPLOADING || status == SyncStatus.COMPRESSING) {
            String worldName = extractBaseWorldName(snapshot.detail());
            if (!worldName.isEmpty() && WorldSyncTask.isWorldNameSafe(worldName)) {
                Path savesDir = getSavesDirectory();
                Path worldFolder = savesDir != null ? savesDir.resolve(worldName) : null;
                Path tempArchive = SyncConfig.getConfigDir().resolve("temp").resolve(worldName + ".tar.zst");
                if ((worldFolder != null && Files.isDirectory(worldFolder)) || Files.exists(tempArchive)) {
                    spawnStandaloneUploader(worldName, worldFolder, tempArchive);
                }
            }
        }

        if (executor != null) {
            executor.shutdownNow();
        }
        if (provider != null) {
            try { provider.shutdown(); } catch (Exception ignored) {}
        }
        clearStatus();
    }

    private static String extractBaseWorldName(String detail) {
        if (detail == null || detail.isEmpty()) return "";
        int parenIdx = detail.indexOf(" (");
        return parenIdx > 0 ? detail.substring(0, parenIdx) : detail;
    }
}
