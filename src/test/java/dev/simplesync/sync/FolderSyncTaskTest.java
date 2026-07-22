package dev.simplesync.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FolderSyncTaskTest {

    @TempDir
    Path tempDir;

    @Test
    public void testScanLocalDirectoryFiltersTempFiles() throws IOException {
        Path schematicsDir = tempDir.resolve("schematics");
        Files.createDirectories(schematicsDir);

        Files.writeString(schematicsDir.resolve("build.litematic"), "data");
        Files.writeString(schematicsDir.resolve("temp.tmp"), "temp");
        Files.writeString(schematicsDir.resolve("backup.bak"), "bak");

        List<FolderSyncTask.LocalFileInfo> scanned = FolderSyncTask.scanLocalDirectory(schematicsDir);

        assertEquals(1, scanned.size());
        assertEquals("build.litematic", scanned.get(0).relativePath());
    }

    @Test
    public void testScanMasaConfigFiles() throws IOException {
        Path configDir = tempDir.resolve("config");
        Path litematicaDir = configDir.resolve("litematica");
        Files.createDirectories(litematicaDir);

        Files.writeString(configDir.resolve("litematica.json"), "{}");
        Files.writeString(configDir.resolve("malilib.json"), "{}");
        Files.writeString(litematicaDir.resolve("area.json"), "{}");

        List<FolderSyncTask.LocalFileInfo> scanned = FolderSyncTask.scanMasaConfigFiles(tempDir);

        assertEquals(3, scanned.size());
        assertTrue(scanned.stream().anyMatch(f -> f.relativePath().equals("config/litematica.json")));
        assertTrue(scanned.stream().anyMatch(f -> f.relativePath().equals("config/malilib.json")));
        assertTrue(scanned.stream().anyMatch(f -> f.relativePath().equals("config/litematica/area.json")));
    }

    @Test
    public void testCreateSyncPlan() {
        FolderSyncTask.LocalFileInfo localOnly = new FolderSyncTask.LocalFileInfo("file1.txt", tempDir.resolve("file1.txt"), 1000L, 100L);
        FolderSyncTask.LocalFileInfo localNewer = new FolderSyncTask.LocalFileInfo("file2.txt", tempDir.resolve("file2.txt"), 5000L, 100L);
        FolderSyncTask.RemoteFileInfo remoteOlder = new FolderSyncTask.RemoteFileInfo("file2.txt", "id2", 1000L, 100L);

        FolderSyncTask.RemoteFileInfo remoteOnly = new FolderSyncTask.RemoteFileInfo("file3.txt", "id3", 2000L, 200L);
        FolderSyncTask.LocalFileInfo localOlder = new FolderSyncTask.LocalFileInfo("file4.txt", tempDir.resolve("file4.txt"), 1000L, 150L);
        FolderSyncTask.RemoteFileInfo remoteNewer = new FolderSyncTask.RemoteFileInfo("file4.txt", "id4", 5000L, 150L);

        List<FolderSyncTask.LocalFileInfo> locals = List.of(localOnly, localNewer, localOlder);
        List<FolderSyncTask.RemoteFileInfo> remotes = List.of(remoteOlder, remoteOnly, remoteNewer);

        FolderSyncTask.SyncPlan plan = FolderSyncTask.createSyncPlan(locals, remotes);

        assertEquals(2, plan.toUpload().size());
        assertTrue(plan.toUpload().contains(localOnly));
        assertTrue(plan.toUpload().contains(localNewer));

        assertEquals(2, plan.toDownload().size());
        assertTrue(plan.toDownload().contains(remoteOnly));
        assertTrue(plan.toDownload().contains(remoteNewer));
    }

    @Test
    public void testCreateSyncPlanPrioritizesCloudForUntrackedLocalFiles() {
        // Simulates a fresh Minecraft instance: local default config has mtime NOW (10000), cloud config has mtime YESTERDAY (5000)
        FolderSyncTask.LocalFileInfo localDefaultConfig = new FolderSyncTask.LocalFileInfo("config/litematica.json", tempDir.resolve("config/litematica.json"), 10000L, 100L);
        FolderSyncTask.RemoteFileInfo remoteCloudConfig = new FolderSyncTask.RemoteFileInfo("config/litematica.json", "id_lite", 5000L, 200L);

        List<FolderSyncTask.LocalFileInfo> locals = List.of(localDefaultConfig);
        List<FolderSyncTask.RemoteFileInfo> remotes = List.of(remoteCloudConfig);

        // Without tracking history (untracked), remote should be downloaded instead of being overwritten
        dev.simplesync.config.SyncConfig.FileTrackingInfo untracked = new dev.simplesync.config.SyncConfig.FileTrackingInfo(0L, 0L, 0L);
        java.util.Map<String, dev.simplesync.config.SyncConfig.FileTrackingInfo> trackingMap = java.util.Map.of("config/litematica.json", untracked);

        FolderSyncTask.SyncPlan plan = FolderSyncTask.createSyncPlan(locals, remotes, trackingMap);

        assertEquals(0, plan.toUpload().size());
        assertEquals(1, plan.toDownload().size());
        assertTrue(plan.toDownload().contains(remoteCloudConfig));
    }
}
