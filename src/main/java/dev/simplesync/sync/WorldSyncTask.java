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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

public class WorldSyncTask {

    private static final int BUFFER_SIZE = 524288; // 512 KB buffer for optimal throughput and CPU cache locality
    private static final long MAX_EXTRACT_SIZE = 50L * 1024 * 1024 * 1024; // 50 GB — large modded worlds can be very big

    private static final String SUFFIX_STAGING = "_staging";
    private static final String SUFFIX_BACKUP = "_backup";
    private static final String SUFFIX_SYNCING = ".syncing";

    private static final java.util.regex.Pattern WINDOWS_RESERVED_NAMES = java.util.regex.Pattern.compile(
            "^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", java.util.regex.Pattern.CASE_INSENSITIVE
    );

    public static boolean isWorldNameSafe(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return false;
        }
        if (worldName.length() > 64) {
            return false;
        }
        if (worldName.contains("..") || worldName.contains("/") || worldName.contains("\\") || worldName.indexOf('\0') >= 0) {
            return false;
        }
        for (int i = 0; i < worldName.length(); i++) {
            char c = worldName.charAt(i);
            if (c == '<' || c == '>' || c == ':' || c == '"' || c == '|' || c == '?' || c == '*' || c < 32) {
                return false;
            }
        }
        if (WINDOWS_RESERVED_NAMES.matcher(worldName.trim()).matches()) {
            return false;
        }
        if (worldName.endsWith(".") || worldName.endsWith(" ")) {
            return false;
        }
        return true;
    }

    private static boolean isArchiveEntryNameSafe(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf(':') >= 0
                || normalized.equals(".") || normalized.equals("..")
                || normalized.startsWith("../") || normalized.contains("/../")
                || normalized.endsWith("/..")) {
            return false;
        }
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) {
                return false;
            }
            if (WINDOWS_RESERVED_NAMES.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }


    /**
     * Compresses a world folder into an archive file (.tar.zst or .zip).
     *
     * @param worldFolder Path to the world folder (e.g., .minecraft/saves/MyWorld)
     * @param outputArchive Path where the archive file will be created
     * @throws IOException if compression fails
     */
    public static void compressWorld(Path worldFolder, Path outputArchive) throws IOException {
        if (!Files.isDirectory(worldFolder)) {
            throw new IOException("World folder does not exist: " + worldFolder);
        }
        if (Files.isSymbolicLink(worldFolder)) {
            throw new IOException("Refusing to compress symlinked world folder: " + worldFolder);
        }

        Files.createDirectories(outputArchive.getParent());
        if (Files.isSymbolicLink(outputArchive)) {
            throw new IOException("Refusing to write archive through symbolic link: " + outputArchive);
        }
        Files.deleteIfExists(outputArchive);

        SimpleSync.LOGGER.info("[SimpleSync] Compressing world: {} -> {}", worldFolder, outputArchive);

        try {
            if (detectFormat(outputArchive) == ArchiveFormat.ZIP) {
                compressWorldZip(worldFolder, outputArchive);
            } else {
                compressWorldTarZst(worldFolder, outputArchive);
            }
        } catch (Throwable e) {
            try {
                Files.deleteIfExists(outputArchive);
            } catch (IOException ignored) {}
            if (e instanceof IOException ioe) throw ioe;
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new IOException("Compression failed", e);
        }

        SimpleSync.LOGGER.info("[SimpleSync] Compression complete: {}", outputArchive);
    }

    @FunctionalInterface
    private interface ArchiveEntryConsumer {
        void accept(Path path, String entryName) throws IOException;
    }

    private static void walkWorldForCompression(Path worldFolder, ArchiveEntryConsumer fileConsumer, ArchiveEntryConsumer dirConsumer) throws IOException {
        Files.walkFileTree(worldFolder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Skipping symlink while compressing world: {}", file);
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString();
                if (name.equals("session.lock") || name.equals("session.lock.backup")) {
                    return FileVisitResult.CONTINUE;
                }

                String entryName = worldFolder.relativize(file).toString().replace('\\', '/');
                fileConsumer.accept(file, entryName);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Skipping symlinked directory while compressing world: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String dirName = dir.getFileName().toString();
                if (dirName.endsWith(SUFFIX_STAGING) || dirName.endsWith(SUFFIX_BACKUP)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String entryName = worldFolder.relativize(dir).toString().replace('\\', '/');
                if (!entryName.isEmpty()) {
                    dirConsumer.accept(dir, entryName);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void compressWorldZip(Path worldFolder, Path outputZip) throws IOException {
        try (OutputStream fileOutputStream = new BufferedOutputStream(Files.newOutputStream(outputZip, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER_SIZE);
             ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream)) {

            walkWorldForCompression(worldFolder, (filePath, entryName) -> {
                zipOutputStream.putNextEntry(new ZipEntry(entryName));
                try (InputStream fileInputStream = Files.newInputStream(filePath)) {
                    transferData(fileInputStream, zipOutputStream);
                }
                zipOutputStream.closeEntry();
            }, (dirPath, entryName) -> {
                zipOutputStream.putNextEntry(new ZipEntry(entryName + "/"));
                zipOutputStream.closeEntry();
            });
        }
    }

    private static void compressWorldTarZst(Path worldFolder, Path outputArchive) throws IOException {
        try (OutputStream fileOutputStream = new BufferedOutputStream(Files.newOutputStream(outputArchive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER_SIZE);
             ZstdCompressorOutputStream zstdOutputStream = new ZstdCompressorOutputStream(fileOutputStream, 1);
             TarArchiveOutputStream tarOutputStream = new TarArchiveOutputStream(zstdOutputStream)) {

            tarOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tarOutputStream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            walkWorldForCompression(worldFolder, (filePath, entryName) -> {
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(Files.size(filePath));
                try {
                    entry.setModTime(Files.getLastModifiedTime(filePath));
                } catch (Exception ignored) {}
                tarOutputStream.putArchiveEntry(entry);
                try (InputStream fileInputStream = Files.newInputStream(filePath)) {
                    transferData(fileInputStream, tarOutputStream);
                }
                tarOutputStream.closeArchiveEntry();
            }, (dirPath, entryName) -> {
                String dirEntryName = entryName.endsWith("/") ? entryName : entryName + "/";
                TarArchiveEntry entry = new TarArchiveEntry(dirEntryName);
                try {
                    entry.setModTime(Files.getLastModifiedTime(dirPath));
                } catch (Exception ignored) {}
                tarOutputStream.putArchiveEntry(entry);
                tarOutputStream.closeArchiveEntry();
            });
        }
    }

    private static void transferData(InputStream inputSource, OutputStream outputDestination) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = inputSource.read(buffer)) > 0) {
            outputDestination.write(buffer, 0, bytesRead);
        }
    }

    /**
     * Extracts an archive file (.tar.zst or .zip) into a world folder, replacing existing contents.
     *
     * @param archiveFile Path to the archive file
     * @param worldFolder Path where the world will be extracted
     * @throws IOException if extraction fails
     */
    public static void extractWorld(Path archiveFile, Path worldFolder) throws IOException {
        if (!Files.exists(archiveFile)) {
            throw new IOException("Archive file does not exist: " + archiveFile);
        }

        Path normalizedTarget = worldFolder.toAbsolutePath().normalize();
        Path stagingDir = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + SUFFIX_STAGING);
        Path backupDir = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + SUFFIX_BACKUP);
        Path syncingMarker = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + SUFFIX_SYNCING);

        SimpleSync.LOGGER.info("[SimpleSync] Extracting world: {} -> {} (via staging)", archiveFile, normalizedTarget);

        Files.writeString(syncingMarker, "syncing");

        if (Files.isDirectory(stagingDir)) {
            deleteDirectoryRecursively(stagingDir);
        }
        Files.createDirectories(stagingDir);

        boolean rollbackFailed = false;
        try {
            if (detectFormat(archiveFile) == ArchiveFormat.ZIP) {
                extractZipEntries(archiveFile, stagingDir);
            } else {
                extractTarZstEntries(archiveFile, stagingDir);
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

            try {
                Files.deleteIfExists(syncingMarker);
            } catch (IOException e) {
                SimpleSync.LOGGER.error("[SimpleSync] Failed to delete syncing marker after successful move: {}", syncingMarker, e);
            }
        } finally {
            if (Files.isDirectory(stagingDir)) {
                deleteDirectoryRecursively(stagingDir);
            }
            if (!rollbackFailed && Files.isDirectory(backupDir)) {
                deleteDirectoryRecursively(backupDir);
            }
            if (!rollbackFailed) {
                try {
                    Files.deleteIfExists(syncingMarker);
                } catch (IOException ignored) {}
            }
        }

        SimpleSync.LOGGER.info("[SimpleSync] Extraction complete: {}", worldFolder);
    }

    private static void extractZipEntries(Path zipFile, Path stagingDir) throws IOException {
        try (InputStream fileInputStream = new BufferedInputStream(Files.newInputStream(zipFile), BUFFER_SIZE);
             ZipInputStream zipInputStream = new ZipInputStream(fileInputStream)) {

            boolean hasEntries = false;
            long totalExtractedBytes = 0;
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                hasEntries = true;
                if (!isArchiveEntryNameSafe(entry.getName())) {
                    throw new IOException("Unsafe ZIP entry name: " + entry.getName());
                }
                Path entryPath = stagingDir.resolve(entry.getName()).normalize();

                // Zip slip protection
                if (!entryPath.startsWith(stagingDir)) {
                    throw new IOException("ZIP entry is outside of target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    if (entryPath.getParent() != null) {
                        Files.createDirectories(entryPath.getParent());
                    }
                    if (Files.isSymbolicLink(entryPath)) {
                        Files.delete(entryPath);
                    }
                    try (OutputStream outputStream = Files.newOutputStream(entryPath)) {
                        totalExtractedBytes = copyWithLimit(zipInputStream, outputStream, totalExtractedBytes, MAX_EXTRACT_SIZE, "zip");
                    }
                }

                zipInputStream.closeEntry();
            }
            if (!hasEntries) {
                throw new IOException("ZIP archive is empty or invalid: " + zipFile);
            }
        }
    }

    private static void extractTarZstEntries(Path archiveFile, Path stagingDir) throws IOException {
        try (InputStream fileInputStream = new BufferedInputStream(Files.newInputStream(archiveFile), BUFFER_SIZE);
             ZstdCompressorInputStream zstdInputStream = new ZstdCompressorInputStream(fileInputStream);
             TarArchiveInputStream tarInputStream = new TarArchiveInputStream(zstdInputStream)) {

            boolean hasEntries = false;
            long totalExtractedBytes = 0;
            TarArchiveEntry entry;
            while ((entry = tarInputStream.getNextTarEntry()) != null) {
                hasEntries = true;
                if (!isArchiveEntryNameSafe(entry.getName())) {
                    throw new IOException("Unsafe archive entry name: " + entry.getName());
                }
                Path entryPath = stagingDir.resolve(entry.getName()).normalize();

                // Tar slip protection
                if (!entryPath.startsWith(stagingDir)) {
                    throw new IOException("Archive entry is outside of target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    if (entryPath.getParent() != null) {
                        Files.createDirectories(entryPath.getParent());
                    }
                    if (Files.isSymbolicLink(entryPath)) {
                        Files.delete(entryPath);
                    }
                    try (OutputStream outputStream = Files.newOutputStream(entryPath)) {
                        totalExtractedBytes = copyWithLimit(tarInputStream, outputStream, totalExtractedBytes, MAX_EXTRACT_SIZE, "archive");
                    }
                }
            }
            if (!hasEntries) {
                throw new IOException("TAR.ZST archive is empty or invalid: " + archiveFile);
            }
        }
    }

    private static long copyWithLimit(InputStream inputSource, OutputStream outputDestination, long alreadyExtractedBytes, long maxTotalBytes, String archiveType) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long totalExtractedBytes = alreadyExtractedBytes;
        while ((bytesRead = inputSource.read(buffer)) > 0) {
            totalExtractedBytes += bytesRead;
            if (totalExtractedBytes > maxTotalBytes) {
                throw new IOException("Extraction aborted: uncompressed data exceeds maximum allowed size ("
                        + (maxTotalBytes / (1024 * 1024 * 1024)) + " GB). Possible " + archiveType + " bomb.");
            }
            outputDestination.write(buffer, 0, bytesRead);
        }
        return totalExtractedBytes;
    }

    /**
     * Scans the saves directory for orphaned _staging and _backup directories left behind by abnormal shutdowns.
     * Cleans up staging folders and restores or deletes backup folders as appropriate.
     */
    public static void cleanupOrphanedDirectories(Path savesDir) {
        if (!Files.isDirectory(savesDir)) {
            return;
        }

        // Phase 1: Clean up interrupted syncs using .syncing files
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String name = entry.getFileName().toString();
                    if (name.endsWith(SUFFIX_SYNCING)) {
                        String worldName = name.substring(0, name.length() - SUFFIX_SYNCING.length());
                        if (!isWorldNameSafe(worldName)) {
                            SimpleSync.LOGGER.warn("[SimpleSync] Skipping interrupted sync cleanup for unsafe world name '{}'", worldName);
                            try { Files.deleteIfExists(entry); } catch (IOException ignored) {}
                            continue;
                        }
                        Path originalWorld = savesDir.resolve(worldName);
                        Path backupDir = savesDir.resolve(worldName + SUFFIX_BACKUP);
                        Path stagingDir = savesDir.resolve(worldName + SUFFIX_STAGING);

                        SimpleSync.LOGGER.warn("[SimpleSync] Detected interrupted sync session for world '{}'", worldName);
                        try {
                            if (Files.isDirectory(backupDir)) {
                                SimpleSync.LOGGER.warn("[SimpleSync] Restoring world '{}' from backup after interrupted sync...", worldName);
                                if (Files.isDirectory(originalWorld)) {
                                    deleteDirectoryRecursively(originalWorld);
                                }
                                Files.move(backupDir, originalWorld);
                            }
                            if (Files.isDirectory(stagingDir)) {
                                deleteDirectoryRecursively(stagingDir);
                            }
                        } catch (IOException e) {
                            SimpleSync.LOGGER.error("[SimpleSync] Failed to recover from interrupted sync for world '{}'", worldName, e);
                        } finally {
                            try {
                                Files.deleteIfExists(entry);
                            } catch (IOException ignored) {}
                        }
                    }
                }
            }
        } catch (IOException e) {
            SimpleSync.LOGGER.error("[SimpleSync] Error during interrupted sync check", e);
        }

        // Phase 2: Normal cleanup of orphaned _staging and _backup
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path entry : stream) {
                if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (name.endsWith(SUFFIX_STAGING)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Cleaning up orphaned staging directory: {}", entry);
                    try {
                        deleteDirectoryRecursively(entry);
                    } catch (IOException e) {
                        SimpleSync.LOGGER.error("[SimpleSync] Failed to delete staging directory: {}", entry, e);
                    }
                } else if (name.endsWith(SUFFIX_BACKUP)) {
                    String originalName = name.substring(0, name.length() - SUFFIX_BACKUP.length());
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
                        try {
                            deleteDirectoryRecursively(entry);
                        } catch (IOException e) {
                            SimpleSync.LOGGER.error("[SimpleSync] Failed to delete backup directory: {}", entry, e);
                        }
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
        Path normalizedRoot = directory.toAbsolutePath().normalize();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Safety: ensure we only delete files within the expected directory
                if (!file.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
                    throw new IOException("Refusing to delete file outside target directory: " + file);
                }
                deleteWithRetry(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (!dir.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
                    throw new IOException("Refusing to delete directory outside target directory: " + dir);
                }
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
                try { Thread.sleep(50); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
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
                if (Files.isSymbolicLink(dir)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Skipping symlinked directory during stats check: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                long mtime = attrs.lastModifiedTime().toMillis();
                if (mtime > stats[1]) {
                    stats[1] = mtime;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(file)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Skipping symlink during stats check: {}", file);
                    return FileVisitResult.CONTINUE;
                }
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

        SyncConfig.WorldTrackingInfo tracking = config.getTracking(worldName);
        long lastSize = tracking.lastLocalSize();
        long lastMtime = tracking.lastLocalMtime();

        if (lastSize == 0 && lastMtime == 0) {
            // Untracked world, needs initial sync/upload
            return true;
        }

        return stats.size() != lastSize || stats.latestModifiedTime() != lastMtime;
    }

    public enum ArchiveFormat {
        ZIP,
        TAR_ZST
    }

    public static ArchiveFormat detectFormat(Path path) {
        try (java.io.InputStream fis = Files.newInputStream(path)) {
            byte[] magic = new byte[4];
            int read = fis.read(magic);
            if (read >= 2 && magic[0] == (byte) 0x50 && magic[1] == (byte) 0x4B) {
                return ArchiveFormat.ZIP;
            }
            if (read >= 4 && magic[0] == (byte) 0x28 && magic[1] == (byte) 0xB5 && magic[2] == (byte) 0x2F && magic[3] == (byte) 0xFD) {
                return ArchiveFormat.TAR_ZST;
            }
        } catch (IOException ignored) {}
        return path.getFileName().toString().endsWith(".zip") ? ArchiveFormat.ZIP : ArchiveFormat.TAR_ZST;
    }
}
