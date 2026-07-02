package dev.simplesync.sync;

import dev.simplesync.config.SyncConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class WorldSyncTaskTest {

    @TempDir
    Path tempDir;

    private Path worldFolder;
    private SyncConfig config;
    private String worldName;

    @BeforeEach
    void setUp() throws IOException {
        worldName = "TestWorld";
        worldFolder = tempDir.resolve(worldName);
        Files.createDirectories(worldFolder);
        config = new SyncConfig();
    }

    @Test
    void testIsLocalWorldModified_FirstSyncReturnsFalse() throws IOException {
        // When lastSize == 0 && lastMtime == 0 (no tracking or first sync), should return false to avoid spurious conflicts
        assertFalse(WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName));
    }

    @Test
    void testIsLocalWorldModified_UnmodifiedWorldReturnsFalse() throws IOException {
        Path file = worldFolder.resolve("level.dat");
        Files.writeString(file, "test data");

        long size = WorldSyncTask.getDirectorySize(worldFolder);
        long mtime = WorldSyncTask.getLatestModifiedTime(worldFolder);

        config.setLastLocalSize(worldName, size);
        config.setLastLocalMtime(worldName, mtime);

        assertFalse(WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName));
    }

    @Test
    void testIsLocalWorldModified_ModifiedWorldReturnsTrue() throws IOException {
        Path file = worldFolder.resolve("level.dat");
        Files.writeString(file, "test data");

        long size = WorldSyncTask.getDirectorySize(worldFolder);
        long mtime = WorldSyncTask.getLatestModifiedTime(worldFolder);

        config.setLastLocalSize(worldName, size);
        config.setLastLocalMtime(worldName, mtime);

        // Modify file
        Files.writeString(file, "modified test data with more bytes");

        assertTrue(WorldSyncTask.isLocalWorldModified(worldFolder, config, worldName));
    }

    @Test
    void testExtractWorld_ZipSlipProtection() throws IOException {
        Path maliciousZip = tempDir.resolve("malicious.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(maliciousZip.toFile()))) {
            ZipEntry entry = new ZipEntry("../evil.txt");
            zos.putNextEntry(entry);
            zos.write("you have been hacked".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Path targetDir = tempDir.resolve("safe_target");
        assertThrows(IOException.class, () -> WorldSyncTask.extractWorld(maliciousZip, targetDir));
        assertFalse(Files.exists(tempDir.resolve("evil.txt")), "Zip-slip attack file should not be extracted!");
    }

    @Test
    void testExtractWorld_RollbackOnFailure() throws IOException {
        Path existingFile = worldFolder.resolve("level.dat");
        Files.writeString(existingFile, "original level data");

        Path corruptZip = tempDir.resolve("corrupt.zip");
        // Create an invalid/truncated zip file or cause failure during extraction
        Files.writeString(corruptZip, "this is not a valid zip archive");

        assertThrows(IOException.class, () -> WorldSyncTask.extractWorld(corruptZip, worldFolder));

        // After failure, the original world folder should be restored from backup!
        assertTrue(Files.exists(worldFolder), "World folder should exist after rollback");
        assertTrue(Files.exists(existingFile), "Original file should exist after rollback");
        assertEquals("original level data", Files.readString(existingFile));
    }
}
