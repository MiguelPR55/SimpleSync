package dev.simplesync.sync;

import dev.simplesync.config.SyncConfig.FileTrackingInfo;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Single-responsibility task for planning and executing incremental folder synchronization.
 * Compares local and remote file metadata to generate an upsert sync plan.
 */
public class FolderSyncTask {

    public record LocalFileInfo(String relativePath, Path fullPath, long lastModified, long size) {}
    public record RemoteFileInfo(String relativePath, String fileId, long lastModified, long size) {}
    public record SyncPlan(List<LocalFileInfo> toUpload, List<RemoteFileInfo> toDownload, List<LocalFileInfo> toDeleteLocally) {}

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(".tmp", ".bak", ".crdownload", ".download", ".staging", ".part", ".lock", ".DS_Store");
    private static final long TIMESTAMP_TOLERANCE_MS = 2000L;

    /**
     * Scans a local directory recursively and returns metadata for all valid files.
     * Uses walkFileTree to obtain file size and timestamps directly from directory attributes.
     */
    public static List<LocalFileInfo> scanLocalDirectory(Path baseDir) throws IOException {
        List<LocalFileInfo> result = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) {
            return result;
        }

        Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(path) || !attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String ext : IGNORED_EXTENSIONS) {
                    if (fileName.endsWith(ext)) return FileVisitResult.CONTINUE;
                }
                String relPath = baseDir.relativize(path).toString().replace('\\', '/');
                result.add(new LocalFileInfo(relPath, path, attrs.lastModifiedTime().toMillis(), attrs.size()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        return result;
    }

    /**
     * Scans specified Masa mod configuration files and folders relative to game root directory.
     */
    public static List<LocalFileInfo> scanMasaConfigFiles(Path gameRootDir) throws IOException {
        Map<String, LocalFileInfo> resultMap = new HashMap<>();

        // Individual JSON files in config/
        List<String> singleConfigFiles = List.of(
                "config/litematica.json",
                "config/tweakeroo.json",
                "config/minihud.json",
                "config/itemscroller.json",
                "config/malilib.json"
        );

        for (String relPath : singleConfigFiles) {
            Path file = gameRootDir.resolve(relPath);
            if (Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    resultMap.put(relPath, new LocalFileInfo(relPath, file, attrs.lastModifiedTime().toMillis(), attrs.size()));
                } catch (IOException ignored) {}
            }
        }

        // Config subdirectories
        List<String> configDirs = List.of(
                "config/litematica",
                "config/tweakeroo",
                "config/minihud",
                "config/itemscroller",
                "config/malilib",
                "itemscroller"
        );

        for (String relDirPath : configDirs) {
            Path dir = gameRootDir.resolve(relDirPath);
            if (Files.isDirectory(dir) && !Files.isSymbolicLink(dir)) {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                        if (Files.isSymbolicLink(path) || !attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        for (String ext : IGNORED_EXTENSIONS) {
                            if (fileName.endsWith(ext)) return FileVisitResult.CONTINUE;
                        }
                        String relPath = gameRootDir.relativize(path).toString().replace('\\', '/');
                        resultMap.put(relPath, new LocalFileInfo(relPath, path, attrs.lastModifiedTime().toMillis(), attrs.size()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                        if (Files.isSymbolicLink(d)) return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }

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
    public static SyncPlan createSyncPlan(List<LocalFileInfo> localFiles, List<RemoteFileInfo> remoteFiles, Map<String, FileTrackingInfo> trackingMap) {
        List<LocalFileInfo> toUpload = new ArrayList<>();
        List<RemoteFileInfo> toDownload = new ArrayList<>();
        List<LocalFileInfo> toDeleteLocally = new ArrayList<>();

        Map<String, FileTrackingInfo> trackingSnapshot =
                trackingMap != null ? new HashMap<>(trackingMap) : Map.of();

        Map<String, RemoteFileInfo> remoteMap = new HashMap<>(remoteFiles.size());
        for (RemoteFileInfo remote : remoteFiles) {
            remoteMap.put(remote.relativePath(), remote);
        }

        Map<String, LocalFileInfo> localMap = new HashMap<>(localFiles.size());
        for (LocalFileInfo local : localFiles) {
            localMap.put(local.relativePath(), local);
            RemoteFileInfo remote = remoteMap.get(local.relativePath());
            if (remote == null) {
                // File does not exist remotely
                FileTrackingInfo tracking = trackingSnapshot.get(local.relativePath());
                long lastSync = tracking != null ? tracking.lastSyncTimestamp() : 0L;
                if (lastSync > 0L) {
                    // Previously synced to cloud, but now deleted from cloud -> delete locally
                    toDeleteLocally.add(local);
                } else {
                    toUpload.add(local);
                }
            } else {
                // File exists both locally and remotely
                if (trackingMap != null) {
                    FileTrackingInfo tracking = trackingSnapshot.get(local.relativePath());
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

        return new SyncPlan(toUpload, toDownload, toDeleteLocally);
    }
}
