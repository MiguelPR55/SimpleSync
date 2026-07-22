package dev.simplesync.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Single-responsibility task for planning and executing incremental folder synchronization.
 * Compares local and remote file metadata to generate an upsert sync plan.
 */
public class FolderSyncTask {

    public record LocalFileInfo(String relativePath, Path fullPath, long lastModified, long size) {}
    public record RemoteFileInfo(String relativePath, String fileId, long lastModified, long size) {}
    public record SyncPlan(List<LocalFileInfo> toUpload, List<RemoteFileInfo> toDownload) {}

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(".tmp", ".bak", ".crdownload", ".DS_Store");
    private static final long TIMESTAMP_TOLERANCE_MS = 2000L;

    /**
     * Scans a local directory recursively and returns metadata for all valid files.
     */
    public static List<LocalFileInfo> scanLocalDirectory(Path baseDir) throws IOException {
        List<LocalFileInfo> result = java.util.Collections.synchronizedList(new ArrayList<>());
        if (!Files.isDirectory(baseDir)) {
            return result;
        }

        try (Stream<Path> stream = Files.walk(baseDir)) {
            stream.parallel().filter(Files::isRegularFile).forEach(path -> {
                String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (IGNORED_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
                    return;
                }
                String relPath = baseDir.relativize(path).toString().replace('\\', '/');
                try {
                    long lastModified = Files.getLastModifiedTime(path).toMillis();
                    long size = Files.size(path);
                    result.add(new LocalFileInfo(relPath, path, lastModified, size));
                } catch (IOException ignored) {}
            });
        }
        return new ArrayList<>(result);
    }

    /**
     * Scans specified Masa mod configuration files and folders relative to game root directory.
     */
    public static List<LocalFileInfo> scanMasaConfigFiles(Path gameRootDir) throws IOException {
        Map<String, LocalFileInfo> resultMap = new java.util.concurrent.ConcurrentHashMap<>();

        // Individual JSON files in config/
        List<String> singleConfigFiles = List.of(
                "config/litematica.json",
                "config/tweakeroo.json",
                "config/minihud.json",
                "config/itemscroller.json",
                "config/malilib.json"
        );

        singleConfigFiles.parallelStream().forEach(relPath -> {
            Path file = gameRootDir.resolve(relPath);
            if (Files.isRegularFile(file)) {
                try {
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    long size = Files.size(file);
                    resultMap.put(relPath, new LocalFileInfo(relPath, file, mtime, size));
                } catch (IOException ignored) {}
            }
        });

        // Config subdirectories
        List<String> configDirs = List.of(
                "config/litematica",
                "config/tweakeroo",
                "config/minihud",
                "config/itemscroller",
                "itemscroller"
        );

        configDirs.parallelStream().forEach(relDirPath -> {
            Path dir = gameRootDir.resolve(relDirPath);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.walk(dir)) {
                    stream.filter(Files::isRegularFile).forEach(path -> {
                        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        if (IGNORED_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
                            return;
                        }
                        String relPath = gameRootDir.relativize(path).toString().replace('\\', '/');
                        try {
                            long mtime = Files.getLastModifiedTime(path).toMillis();
                            long size = Files.size(path);
                            resultMap.put(relPath, new LocalFileInfo(relPath, path, mtime, size));
                        } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            }
        });

        return new ArrayList<>(resultMap.values());
    }

    /**
     * Creates an incremental sync plan by comparing local files against remote metadata.
     * Uses upsert strategy (does not delete missing files on either side).
     */
    public static SyncPlan createSyncPlan(List<LocalFileInfo> localFiles, List<RemoteFileInfo> remoteFiles) {
        return createSyncPlan(localFiles, remoteFiles, null);
    }

    /**
     * Creates an incremental sync plan with file tracking information.
     * If a local file exists but is untracked (never synced by this instance) and a remote file exists,
     * the remote file is prioritized for download to protect cloud configurations from default overwrites.
     */
    public static SyncPlan createSyncPlan(List<LocalFileInfo> localFiles, List<RemoteFileInfo> remoteFiles, Map<String, dev.simplesync.config.SyncConfig.FileTrackingInfo> trackingMap) {
        List<LocalFileInfo> toUpload = new ArrayList<>();
        List<RemoteFileInfo> toDownload = new ArrayList<>();

        Map<String, RemoteFileInfo> remoteMap = new HashMap<>();
        for (RemoteFileInfo remote : remoteFiles) {
            remoteMap.put(remote.relativePath(), remote);
        }

        Map<String, LocalFileInfo> localMap = new HashMap<>();
        for (LocalFileInfo local : localFiles) {
            localMap.put(local.relativePath(), local);
            RemoteFileInfo remote = remoteMap.get(local.relativePath());
            if (remote == null) {
                // File does not exist remotely -> upload
                toUpload.add(local);
            } else {
                // File exists both locally and remotely
                if (trackingMap != null) {
                    dev.simplesync.config.SyncConfig.FileTrackingInfo tracking = trackingMap.get(local.relativePath());
                    long lastSync = tracking != null ? tracking.lastSyncTimestamp() : 0L;

                    if (lastSync == 0L) {
                        // Untracked local file (e.g. freshly generated default config) vs existing cloud file.
                        // Prioritize cloud version to protect remote user configs!
                        toDownload.add(remote);
                    } else {
                        boolean localModifiedAfterSync = local.lastModified() > lastSync + TIMESTAMP_TOLERANCE_MS;
                        boolean remoteModifiedAfterSync = remote.lastModified() > lastSync + TIMESTAMP_TOLERANCE_MS;

                        if (localModifiedAfterSync && !remoteModifiedAfterSync) {
                            toUpload.add(local);
                        } else if (remoteModifiedAfterSync && !localModifiedAfterSync) {
                            toDownload.add(remote);
                        } else if (localModifiedAfterSync && remoteModifiedAfterSync) {
                            // Both modified after sync -> pick newer
                            if (local.lastModified() > remote.lastModified() + TIMESTAMP_TOLERANCE_MS) {
                                toUpload.add(local);
                            } else if (remote.lastModified() > local.lastModified() + TIMESTAMP_TOLERANCE_MS) {
                                toDownload.add(remote);
                            }
                        }
                    }
                } else {
                    // Fallback when trackingMap is null (legacy behavior without tracking)
                    if (local.lastModified() > remote.lastModified() + TIMESTAMP_TOLERANCE_MS) {
                        toUpload.add(local);
                    } else if (remote.lastModified() > local.lastModified() + TIMESTAMP_TOLERANCE_MS) {
                        toDownload.add(remote);
                    }
                }
            }
        }

        for (RemoteFileInfo remote : remoteFiles) {
            LocalFileInfo local = localMap.get(remote.relativePath());
            if (local == null) {
                // Remote file does not exist locally -> download
                toDownload.add(remote);
            }
        }

        return new SyncPlan(toUpload, toDownload);
    }
}
