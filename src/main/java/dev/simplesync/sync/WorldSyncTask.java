package dev.simplesync.sync;

import dev.simplesync.SimpleSync;

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

        // Delete session.lock if it exists (prevents issues during sync)
        Path sessionLock = worldFolder.resolve("session.lock");
        if (Files.exists(sessionLock)) {
            try {
                Files.delete(sessionLock);
            } catch (IOException e) {
                SimpleSync.LOGGER.warn("[SimpleSync] Could not delete session.lock: {}", e.getMessage());
            }
        }

        SimpleSync.LOGGER.info("[SimpleSync] Compressing world: {} -> {}", worldFolder, outputZip);

        try (OutputStream fos = Files.newOutputStream(outputZip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            Files.walkFileTree(worldFolder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }

                    String entryName = worldFolder.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entryName));

                    try (InputStream fis = Files.newInputStream(file)) {
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
                    String entryName = worldFolder.relativize(dir).toString().replace('\\', '/');
                    if (!entryName.isEmpty()) {
                        zos.putNextEntry(new ZipEntry(entryName + "/"));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
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
        SimpleSync.LOGGER.info("[SimpleSync] Extracting world: {} -> {}", zipFile, normalizedTarget);

        if (Files.isDirectory(normalizedTarget)) {
            deleteDirectoryRecursively(normalizedTarget);
        }
        Files.createDirectories(normalizedTarget);

        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = normalizedTarget.resolve(entry.getName()).normalize();

                // Security check: prevent zip slip attack
                if (!entryPath.startsWith(normalizedTarget)) {
                    throw new IOException("ZIP entry is outside of target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream fos = Files.newOutputStream(entryPath)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }

                zis.closeEntry();
            }
        }

        SimpleSync.LOGGER.info("[SimpleSync] Extraction complete: {}", worldFolder);
    }

    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static long getDirectorySize(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }

        final long[] size = {0};
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                size[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }
}
