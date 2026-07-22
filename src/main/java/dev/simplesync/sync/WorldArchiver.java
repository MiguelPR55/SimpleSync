package dev.simplesync.sync;

import dev.simplesync.SimpleSync;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.zstandard.*;

/**
 * Handles compression and extraction of world archives (tar.zst and zip).
 * Unified logic with format-specific readers/writers behind a common interface.
 */
public class WorldArchiver {

    private static final int BUFFER_SIZE = 262_144;
    private static final long MAX_EXTRACT_SIZE = 50L * 1024 * 1024 * 1024;
    private static final String SUFFIX_STAGING = "_staging";
    private static final String SUFFIX_BACKUP = "_backup";
    private static final String SUFFIX_SYNCING = ".syncing";

    // ─── Public API ───────────────────────────────────────────────────────

    public static void compressWorld(Path worldFolder, Path outputArchive) throws IOException {
        if (!Files.isDirectory(worldFolder)) throw new IOException("World folder does not exist: " + worldFolder);
        if (Files.isSymbolicLink(worldFolder)) throw new IOException("Refusing to compress symlinked folder");
        Files.createDirectories(outputArchive.getParent());
        if (Files.isSymbolicLink(outputArchive)) throw new IOException("Refusing to write through symlink");
        Files.deleteIfExists(outputArchive);

        SimpleSync.LOGGER.info("[SimpleSync] Compressing: {} -> {}", worldFolder, outputArchive);
        try {
            boolean isZip = outputArchive.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
            if (isZip) {
                compressZip(worldFolder, outputArchive);
            } else {
                compressTarZst(worldFolder, outputArchive);
            }
        } catch (Throwable e) {
            try { Files.deleteIfExists(outputArchive); } catch (IOException ignored) {}
            if (e instanceof IOException ioe) throw ioe;
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new IOException("Compression failed", e);
        }
    }

    public static void extractWorld(Path archiveFile, Path worldFolder) throws IOException {
        if (!Files.exists(archiveFile)) throw new IOException("Archive does not exist: " + archiveFile);
        if (Files.size(archiveFile) == 0) throw new IOException("Archive is empty (0 bytes): " + archiveFile);

        Path target = worldFolder.toAbsolutePath().normalize();
        Path stagingDir = target.resolveSibling(target.getFileName() + SUFFIX_STAGING);
        Path backupDir = target.resolveSibling(target.getFileName() + SUFFIX_BACKUP);
        Path syncingMarker = target.resolveSibling(target.getFileName() + SUFFIX_SYNCING);

        SimpleSync.LOGGER.info("[SimpleSync] Extracting: {} -> {}", archiveFile, target);
        Files.writeString(syncingMarker, "syncing");

        if (Files.isDirectory(stagingDir)) deleteRecursively(stagingDir);
        Files.createDirectories(stagingDir);

        boolean rollbackFailed = false;
        try {
            // Extract to staging
            if (detectFormat(archiveFile) == ArchiveFormat.ZIP) {
                extractEntries(archiveFile, stagingDir, ArchiveFormat.ZIP);
            } else {
                extractEntries(archiveFile, stagingDir, ArchiveFormat.TAR_ZST);
            }

            // Move current world to backup
            if (Files.isDirectory(target)) {
                if (Files.isDirectory(backupDir)) deleteRecursively(backupDir);
                Files.move(target, backupDir, StandardCopyOption.REPLACE_EXISTING);
            }

            // Move staging to target
            try {
                Files.move(stagingDir, target);
            } catch (IOException e) {
                if (Files.isDirectory(backupDir)) {
                    try {
                        Files.move(backupDir, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException rollbackEx) {
                        rollbackFailed = true;
                        e.addSuppressed(rollbackEx);
                        throw new IOException("CRITICAL: Extraction and rollback both failed", e);
                    }
                }
                throw e;
            }

            try { Files.deleteIfExists(syncingMarker); } catch (IOException ignored) {}
        } finally {
            if (Files.isDirectory(stagingDir)) deleteRecursively(stagingDir);
            if (!rollbackFailed && Files.isDirectory(backupDir)) deleteRecursively(backupDir);
            if (!rollbackFailed) { try { Files.deleteIfExists(syncingMarker); } catch (IOException ignored) {} }
        }
    }

    // ─── Unified Extraction (eliminates duplication) ──────────────────────

    private static void extractEntries(Path archiveFile, Path stagingDir, ArchiveFormat format) throws IOException {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(archiveFile), BUFFER_SIZE);
             ArchiveEntryReader reader = createReader(fis, format)) {

            boolean hasEntries = false;
            long totalExtracted = 0;
            ArchiveEntryInfo entry;

            while ((entry = reader.nextEntry()) != null) {
                hasEntries = true;
                if (!WorldNameValidator.isArchiveEntryNameSafe(entry.name())) {
                    throw new IOException("Unsafe entry name: " + entry.name());
                }
                Path entryPath = stagingDir.resolve(entry.name()).normalize();
                if (!entryPath.startsWith(stagingDir)) {
                    throw new IOException("Entry outside target (zip-slip): " + entry.name());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    if (entryPath.getParent() != null) Files.createDirectories(entryPath.getParent());
                    if (Files.isSymbolicLink(entryPath)) Files.delete(entryPath);
                    try (OutputStream os = Files.newOutputStream(entryPath)) {
                        totalExtracted = copyWithLimit(reader.currentStream(), os, totalExtracted);
                    }
                }
            }
            if (!hasEntries) throw new IOException("Archive is empty: " + archiveFile);
        }
    }

    // ─── Archive Reader Abstraction ───────────────────────────────────────

    record ArchiveEntryInfo(String name, boolean isDirectory) {}

    interface ArchiveEntryReader extends Closeable {
        ArchiveEntryInfo nextEntry() throws IOException;
        InputStream currentStream();
    }

    private static ArchiveEntryReader createReader(InputStream fis, ArchiveFormat format) throws IOException {
        if (format == ArchiveFormat.ZIP) {
            ZipInputStream zis = new ZipInputStream(fis);
            return new ArchiveEntryReader() {
                private ZipEntry current;
                @Override public ArchiveEntryInfo nextEntry() throws IOException {
                    current = zis.getNextEntry();
                    if (current == null) return null;
                    return new ArchiveEntryInfo(current.getName(), current.isDirectory());
                }
                @Override public InputStream currentStream() { return zis; }
                @Override public void close() throws IOException { zis.close(); }
            };
        } else {
            ZstdCompressorInputStream zis = new ZstdCompressorInputStream(fis);
            TarArchiveInputStream tis = new TarArchiveInputStream(zis);
            return new ArchiveEntryReader() {
                @Override public ArchiveEntryInfo nextEntry() throws IOException {
                    TarArchiveEntry entry = tis.getNextTarEntry();
                    if (entry == null) return null;
                    return new ArchiveEntryInfo(entry.getName(), entry.isDirectory());
                }
                @Override public InputStream currentStream() { return tis; }
                @Override public void close() throws IOException { tis.close(); }
            };
        }
    }

    // ─── Compression ──────────────────────────────────────────────────────

    @FunctionalInterface
    private interface EntryConsumer { void accept(Path path, String entryName) throws IOException; }

    private static void walkWorld(Path worldFolder, EntryConsumer fileConsumer, EntryConsumer dirConsumer) throws IOException {
        Files.walkFileTree(worldFolder, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (name.equals("session.lock") || name.equals("session.lock.backup") || name.equals("level.dat_old") || name.equals("uid.dat")) return FileVisitResult.CONTINUE;
                if (name.endsWith(".tmp") || name.endsWith(".lock") || name.endsWith(".part") || name.endsWith(".syncing") || name.endsWith(".download") || name.endsWith(".staging")) return FileVisitResult.CONTINUE;
                fileConsumer.accept(file, worldFolder.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir) || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.SKIP_SUBTREE;
                String dirName = dir.getFileName().toString();
                if (dirName.endsWith(SUFFIX_STAGING) || dirName.endsWith(SUFFIX_BACKUP)) return FileVisitResult.SKIP_SUBTREE;
                String entryName = worldFolder.relativize(dir).toString().replace('\\', '/');
                if (!entryName.isEmpty()) dirConsumer.accept(dir, entryName);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void compressZip(Path worldFolder, Path output) throws IOException {
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        try (var fos = new BufferedOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), BUFFER_SIZE);
             var zos = new ZipOutputStream(fos)) {
            walkWorld(worldFolder,
                    (file, name) -> { zos.putNextEntry(new ZipEntry(name)); try (var is = Files.newInputStream(file)) { transfer(is, zos); } zos.closeEntry(); },
                    (dir, name) -> { zos.putNextEntry(new ZipEntry(name + "/")); zos.closeEntry(); });
        }
    }

    private static void compressTarZst(Path worldFolder, Path output) throws IOException {
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        try (var fos = new BufferedOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), BUFFER_SIZE);
             var zos = new ZstdCompressorOutputStream(fos);
             var tos = new TarArchiveOutputStream(zos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            walkWorld(worldFolder,
                    (file, name) -> {
                        var entry = new TarArchiveEntry(name);
                        entry.setSize(Files.size(file));
                        try { entry.setModTime(Files.getLastModifiedTime(file)); } catch (Exception ignored) {}
                        tos.putArchiveEntry(entry);
                        try (var is = Files.newInputStream(file)) { transfer(is, tos); }
                        tos.closeArchiveEntry();
                    },
                    (dir, name) -> {
                        var entry = new TarArchiveEntry(name.endsWith("/") ? name : name + "/");
                        try { entry.setModTime(Files.getLastModifiedTime(dir)); } catch (Exception ignored) {}
                        tos.putArchiveEntry(entry);
                        tos.closeArchiveEntry();
                    });
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────

    private static void transfer(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static long copyWithLimit(InputStream in, OutputStream out, long alreadyExtracted) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        long total = alreadyExtracted;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_EXTRACT_SIZE) {
                throw new IOException("Extraction exceeds " + (MAX_EXTRACT_SIZE / (1024*1024*1024)) + " GB limit. Possible archive bomb.");
            }
            out.write(buf, 0, n);
        }
        return total;
    }

    static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        Path root = directory.toAbsolutePath().normalize();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Outside target: " + file);
                deleteWithRetry(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (!dir.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Outside target: " + dir);
                deleteWithRetry(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteWithRetry(Path path) throws IOException {
        try { Files.setAttribute(path, "dos:readonly", false); } catch (Exception ignored) {}
        for (int i = 0; i < 3; i++) {
            try { Files.deleteIfExists(path); return; }
            catch (IOException e) {
                if (i == 2) throw e;
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    public enum ArchiveFormat { ZIP, TAR_ZST }

    public static ArchiveFormat detectFormat(Path path) throws IOException {
        try (var fis = Files.newInputStream(path)) {
            byte[] magic = new byte[4];
            int read = fis.read(magic);
            if (read >= 2 && magic[0] == (byte) 0x50 && magic[1] == (byte) 0x4B) return ArchiveFormat.ZIP;
            if (read >= 4 && magic[0] == (byte) 0x28 && magic[1] == (byte) 0xB5 && magic[2] == (byte) 0x2F && magic[3] == (byte) 0xFD) return ArchiveFormat.TAR_ZST;
            String headerPreview = read > 0 ? new String(magic, 0, read, java.nio.charset.StandardCharsets.UTF_8) : "empty";
            throw new IOException("Invalid archive magic header for '" + path.getFileName() + "'. Header preview: '" + headerPreview + "'");
        }
    }
}
