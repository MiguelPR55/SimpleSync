package dev.simplesync.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class SyncConfigTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        SyncConfig.setConfigDir(tempDir);
        SyncConfig.resetInstance();
    }

    @Test
    void testDefaultValues() {
        SyncConfig config = new SyncConfig();
        assertTrue(config.autoSyncOnStart);
        assertTrue(config.autoSyncOnExit);
        assertEquals("google_drive", config.cloudProvider);
        assertNotNull(config.worldTracking);
        assertNotNull(config.ignoredCloudWorlds);
    }

    @Test
    void testGetAndSetTracking() {
        SyncConfig config = new SyncConfig();
        SyncConfig.WorldTrackingInfo info = new SyncConfig.WorldTrackingInfo(1000L, 5000L, 2000L);
        config.setTracking("MyWorld", info);

        SyncConfig.WorldTrackingInfo retrieved = config.getTracking("MyWorld");
        assertEquals(1000L, retrieved.lastSyncTimestamp());
        assertEquals(5000L, retrieved.lastLocalSize());
        assertEquals(2000L, retrieved.lastLocalMtime());

        SyncConfig.WorldTrackingInfo untracked = config.getTracking("UntrackedWorld");
        assertEquals(0L, untracked.lastSyncTimestamp());
        assertEquals(0L, untracked.lastLocalSize());
        assertEquals(0L, untracked.lastLocalMtime());
    }

    @Test
    void testRemoveTracking() {
        SyncConfig config = new SyncConfig();
        config.setTracking("World1", new SyncConfig.WorldTrackingInfo(100L, 200L, 300L));
        config.removeTracking("World1");

        SyncConfig.WorldTrackingInfo info = config.getTracking("World1");
        assertEquals(0L, info.lastSyncTimestamp());
    }

    @Test
    void testIgnoredWorldsSet() {
        SyncConfig config = new SyncConfig();
        config.ignoredCloudWorlds.add("ArchivedWorld");

        assertTrue(config.ignoredCloudWorlds.contains("ArchivedWorld"));
        config.ignoredCloudWorlds.remove("ArchivedWorld");
        assertFalse(config.ignoredCloudWorlds.contains("ArchivedWorld"));
    }

    @Test
    void testSaveAndLoadPersistence() {
        SyncConfig config = SyncConfig.load();
        config.autoSyncOnStart = false;
        config.setTracking("SurvivalWorld", new SyncConfig.WorldTrackingInfo(12345L, 67890L, 11223L));
        config.ignoredCloudWorlds.add("OldWorld");
        config.save();

        SyncConfig.resetInstance();
        SyncConfig loaded = SyncConfig.load();

        assertFalse(loaded.autoSyncOnStart);
        assertEquals(12345L, loaded.getTracking("SurvivalWorld").lastSyncTimestamp());
        assertEquals(67890L, loaded.getTracking("SurvivalWorld").lastLocalSize());
        assertTrue(loaded.ignoredCloudWorlds.contains("OldWorld"));
    }

    @Test
    void testLegacyMigration() throws IOException {
        String legacyJson = """
                {
                    "autoSyncOnStart": true,
                    "lastSyncTimestamps": { "LegacyWorld": 99999 },
                    "lastLocalSizes": { "LegacyWorld": 88888 },
                    "lastLocalMtimes": { "LegacyWorld": 77777 }
                }
                """;
        Files.writeString(tempDir.resolve("config.json"), legacyJson);

        SyncConfig config = SyncConfig.load();
        SyncConfig.WorldTrackingInfo migrated = config.getTracking("LegacyWorld");

        assertEquals(99999L, migrated.lastSyncTimestamp());
        assertEquals(88888L, migrated.lastLocalSize());
        assertEquals(77777L, migrated.lastLocalMtime());
    }

    @Test
    void testCorruptedConfigRecoversAndBacksUp() throws IOException {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{ this is invalid json !!!");

        SyncConfig config = SyncConfig.load();
        assertNotNull(config);
        assertTrue(config.autoSyncOnStart);
        assertTrue(Files.exists(tempDir.resolve("config.json.corrupted")), "Corrupted config must be backed up");
    }

    @Test
    void testFolderIdsPersistenceAndResetting() {
        SyncConfig config = SyncConfig.load();
        config.simpleSyncFolderId = "root_123";
        config.worldsFolderId = "worlds_456";
        config.schematicsFolderId = "schematics_789";
        config.configsFolderId = "configs_012";
        config.save();

        SyncConfig.resetInstance();
        SyncConfig loaded = SyncConfig.load();

        assertEquals("root_123", loaded.simpleSyncFolderId);
        assertEquals("worlds_456", loaded.worldsFolderId);
        assertEquals("schematics_789", loaded.schematicsFolderId);
        assertEquals("configs_012", loaded.configsFolderId);

        // Resetting (simulating disconnect)
        loaded.simpleSyncFolderId = null;
        loaded.worldsFolderId = null;
        loaded.schematicsFolderId = null;
        loaded.configsFolderId = null;
        loaded.save();

        SyncConfig.resetInstance();
        SyncConfig reloaded = SyncConfig.load();
        assertNull(reloaded.simpleSyncFolderId);
        assertNull(reloaded.worldsFolderId);
        assertNull(reloaded.schematicsFolderId);
        assertNull(reloaded.configsFolderId);
    }
}
