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
}
