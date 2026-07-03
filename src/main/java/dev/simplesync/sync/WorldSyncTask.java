package dev.simplesync.sync;

import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class WorldSyncTask {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Compresses a world folder into a ZIP file.
     *
     * @param worldFolder Path to the world folder (e.g., .minecraft/saves/MyWorld)
     * @param outputZip   Path where the ZIP file will be created
     * @throws IOException if compression fails
     */
    public static void compressWorld(Path worldFolder, Path outputZip) throws IOException {
        if (!Files.isDirectory(worldFolder)) {
            throw new IOException("World folder does not exist: " + worldFolder);
        }

        Files.createDirectories(outputZip.getParent());

        // Delete session.lock if it exists (prevents issues during sync, retry for Windows handles)
        Path sessionLock = worldFolder.resolve("session.lock");
        Path sessionLockBackup = worldFolder.resolve("session.lock.backup");
        boolean backedUpLock = false;
        if (Files.exists(sessionLock)) {
            try {
                Files.copy(sessionLock, sessionLockBackup, StandardCopyOption.REPLACE_EXISTING);
                backedUpLock = true;
            } catch (IOException e) {
                SimpleSync.LOGGER.warn("[SimpleSync] Could not back up session.lock, proceeding directly: {}", e.getMessage());
            }

            for (int i = 0; i < 5; i++) {
                try {
                    Files.delete(sessionLock);
                    break;
                } catch (IOException e) {
                    if (i == 4) {
                        SimpleSync.LOGGER.warn("[SimpleSync] Could not delete session.lock after retries: {}", e.getMessage());
                    } else {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        }

        SimpleSync.LOGGER.info("[SimpleSync] Compressing world: {} -> {}", worldFolder, outputZip);

        try {
            try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(outputZip), 65536);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                Files.walkFileTree(worldFolder, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String name = file.getFileName().toString();
                        if (name.equals("session.lock") || name.equals("session.lock.backup")) {
                            return FileVisitResult.CONTINUE;
                        }

                        String entryName = worldFolder.relativize(file).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entryName));

                        try (InputStream fis = new BufferedInputStream(Files.newInputStream(file), 65536)) {
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        }

                        zos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        String dirName = dir.getFileName().toString();
                        if (dirName.endsWith("_staging") || dirName.endsWith("_backup")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        String entryName = worldFolder.relativize(dir).toString().replace('\\', '/');
                        if (!entryName.isEmpty()) {
                            zos.putNextEntry(new ZipEntry(entryName + "/"));
                            zos.closeEntry();
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (Exception e) {
            if (backedUpLock) {
                try {
                    Files.move(sessionLockBackup, sessionLock, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException restoreEx) {
                    SimpleSync.LOGGER.error("[SimpleSync] Failed to restore session.lock after compression failure", restoreEx);
                }
            }
            try {
                Files.deleteIfExists(outputZip);
            } catch (IOException ignored) {}
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException("Compression failed", e);
        } finally {
            try {
                Files.deleteIfExists(sessionLockBackup);
            } catch (IOException ignored) {}
        }

        SimpleSync.LOGGER.info("[SimpleSync] Compression complete: {}", outputZip);
    }

    /**
     * Extracts a ZIP file into a world folder, replacing existing contents.
     *
     * @param zipFile     Path to the ZIP file
     * @param worldFolder Path where the world will be extracted
     * @throws IOException if extraction fails
     */
    public static void extractWorld(Path zipFile, Path worldFolder) throws IOException {
        if (!Files.exists(zipFile)) {
            throw new IOException("ZIP file does not exist: " + zipFile);
        }

        Path normalizedTarget = worldFolder.normalize();
        Path stagingDir = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + "_staging");
        Path backupDir = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + "_backup");

        SimpleSync.LOGGER.info("[SimpleSync] Extracting world: {} -> {} (via staging)", zipFile, normalizedTarget);

        if (Files.isDirectory(stagingDir)) {
            deleteDirectoryRecursively(stagingDir);
        }
        Files.createDirectories(stagingDir);

        boolean rollbackFailed = false;
        try {
            try (InputStream fis = new BufferedInputStream(Files.newInputStream(zipFile), 65536);
                 ZipInputStream zis = new ZipInputStream(fis)) {

                boolean hasEntries = false;
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    hasEntries = true;
                    Path entryPath = stagingDir.resolve(entry.getName()).normalize();

                    // Security check: prevent zip slip attack
                    if (!entryPath.startsWith(stagingDir)) {
                        throw new IOException("ZIP entry is outside of target directory: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(entryPath), 65536)) {
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }
                    }

                    zis.closeEntry();
                }
                if (!hasEntries) {
                    throw new IOException("ZIP archive is empty or invalid: " + zipFile);
                }
            }

            if (Files.isDirectory(normalizedTarget)) {
                if (Files.isDirectory(backupDir)) {
                    deleteDirectoryRecursively(backupDir);
                }
                Files.move(normalizedTarget, backupDir, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(stagingDir, normalizedTarget);
            } catch (IOException e) {
                if (Files.isDirectory(backupDir)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Extraction move failed, rolling back from backup...");
                    try {
                        Files.move(backupDir, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException rollbackEx) {
                        rollbackFailed = true;
                        SimpleSync.LOGGER.error("[SimpleSync] CRITICAL: Failed to rollback backup! Backup preserved at: {}", backupDir, rollbackEx);
                        e.addSuppressed(rollbackEx);
                        throw new IOException("Extraction failed and CRITICAL rollback failure occurred: " + rollbackEx.getMessage(), e);
                    }
                }
                throw e;
            }
        } finally {
            if (Files.isDirectory(stagingDir)) {
                deleteDirectoryRecursively(stagingDir);
            }
            if (!rollbackFailed && Files.isDirectory(backupDir)) {
                deleteDirectoryRecursively(backupDir);
            }
        }

        SimpleSync.LOGGER.info("[SimpleSync] Extraction complete: {}", worldFolder);
    }

    /**
     * Scans the saves directory for orphaned _staging and _backup directories left behind by abnormal shutdowns.
     * Cleans up staging folders and restores or deletes backup folders as appropriate.
     */
    public static void cleanupOrphanedDirectories(Path savesDir) {
        if (!Files.isDirectory(savesDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (name.endsWith("_staging")) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Cleaning up orphaned staging directory: {}", entry);
                    deleteDirectoryRecursively(entry);
                } else if (name.endsWith("_backup")) {
                    String originalName = name.substring(0, name.length() - "_backup".length());
                    Path originalWorld = savesDir.resolve(originalName);
                    if (!Files.exists(originalWorld)) {
                        SimpleSync.LOGGER.warn("[SimpleSync] Restoring orphaned backup directory to original world: {} -> {}", entry, originalWorld);
                        try {
                            Files.move(entry, originalWorld);
                        } catch (IOException e) {
                            SimpleSync.LOGGER.error("[SimpleSync] Failed to restore orphaned backup directory: {}", entry, e);
                        }
                    } else {
                        SimpleSync.LOGGER.warn("[SimpleSync] Cleaning up leftover backup directory: {}", entry);
                        deleteDirectoryRecursively(entry);
                    }
                }
            }
        } catch (IOException e) {
            SimpleSync.LOGGER.error("[SimpleSync] Error during orphaned directories cleanup", e);
        }
    }

    /**
     * Recursively deletes a directory tree.
     * Note: SimpleFileVisitor does not follow symlinks by default. If symlinks exist within the directory,
     * the symlink file itself is deleted without affecting or traversing into the target location.
     */
    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteWithRetry(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                deleteWithRetry(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteWithRetry(Path path) throws IOException {
        for (int i = 0; i < 3; i++) {
            try {
                Files.deleteIfExists(path);
                break;
            } catch (IOException e) {
                if (i == 2) throw e;
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
    }

    public record WorldStats(long size, long latestModifiedTime) {}

    public static WorldStats getWorldStats(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return new WorldStats(0, 0);
        }
        final long[] stats = {0, 0}; // [size, maxTime]
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                long mtime = attrs.lastModifiedTime().toMillis();
                if (mtime > stats[1]) {
                    stats[1] = mtime;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                stats[0] += attrs.size();
                long mtime = attrs.lastModifiedTime().toMillis();
                if (mtime > stats[1]) {
                    stats[1] = mtime;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                SimpleSync.LOGGER.warn("[SimpleSync] Could not read file attributes during stats check (permissions/symlink): {}", file);
                return FileVisitResult.CONTINUE;
            }
        });
        return new WorldStats(stats[0], stats[1]);
    }

    public static long getDirectorySize(Path directory) throws IOException {
        return getWorldStats(directory).size();
    }

    public static long getLatestModifiedTime(Path directory) throws IOException {
        return getWorldStats(directory).latestModifiedTime();
    }

    public static boolean isLocalWorldModified(Path worldFolder, SyncConfig config, String worldName) throws IOException {
        return isLocalWorldModified(worldFolder, config, worldName, getWorldStats(worldFolder));
    }

    /**
     * Checks if local world is modified using precalculated WorldStats to avoid scanning filesystem twice.
     * Compares exact size and latest modified time against recorded values.
     */
    public static boolean isLocalWorldModified(Path worldFolder, SyncConfig config, String worldName, WorldStats stats) throws IOException {
        if (!Files.isDirectory(worldFolder)) {
            return false;
        }

        long lastSize = config.getLastLocalSize(worldName);
        long lastMtime = config.getLastLocalMtime(worldName);

        if (lastSize == 0 && lastMtime == 0) {
            // Untracked world, needs initial sync/upload
            return true;
        }

        return stats.size() != lastSize || stats.latestModifiedTime() != lastMtime;
    }
}
