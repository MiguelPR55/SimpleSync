package dev.simplesync.cloud;

import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class WorldSyncStateTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        SyncConfig.setConfigDir(tempDir.resolve("config"));
        SyncConfig config = new SyncConfig();
        config.save();
        SyncConfig.resetInstance();

        CloudSyncManager.getInstance().resetStateForTests();
        CloudSyncManager.getInstance().setSavesDirectory(tempDir.resolve("saves"));
        Files.createDirectories(tempDir.resolve("saves"));
    }

    @Test
    void testWorldSynchronizationLifecycle() {
        CloudSyncManager manager = CloudSyncManager.getInstance();
        String worldName = "Survival_1";

        // Initially before sync, world is not marked synchronized
        manager.markWorldUnsynchronized(worldName);
        assertFalse(manager.isWorldSynchronized(worldName));
        assertFalse(manager.isWorldBusy(worldName));

        // When marked synchronized
        manager.markWorldSynchronized(worldName);
        assertTrue(manager.isWorldSynchronized(worldName));
        assertFalse(manager.isWorldBusy(worldName));

        // When actively transferring, isWorldBusy returns true and isWorldSynchronized returns false
        manager.setStatus(SyncStatus.UPLOADING, worldName + " (45%)");
        assertTrue(manager.isWorldBusy(worldName));
        assertFalse(manager.isWorldSynchronized(worldName));

        // When another world is syncing, Survival_1 is not busy
        manager.setStatus(SyncStatus.DOWNLOADING, "Creative_2");
        assertFalse(manager.isWorldBusy(worldName));
        assertTrue(manager.isWorldSynchronized(worldName));
        assertTrue(manager.isWorldBusy("Creative_2"));

        // Clear status
        manager.clearStatus();
        assertFalse(manager.isWorldBusy(worldName));
        assertTrue(manager.isWorldSynchronized(worldName));
    }

    @Test
    void testMarkInitialSyncCompleted() throws IOException {
        CloudSyncManager manager = CloudSyncManager.getInstance();
        Path savesDir = tempDir.resolve("saves");
        Files.createDirectories(savesDir.resolve("WorldA"));
        Files.createDirectories(savesDir.resolve("WorldB"));

        manager.markInitialSyncCompleted();
        assertTrue(manager.isInitialSyncCompleted());
        assertTrue(manager.isWorldSynchronized("WorldA"));
        assertTrue(manager.isWorldSynchronized("WorldB"));
        assertFalse(manager.isWorldSynchronized("NonExistentWorld"));
    }

    @Test
    void testActiveRunningWorldCheck() {
        assertFalse(SimpleSync.isWorldRunning("MyWorld"));
        assertNull(SimpleSync.getActiveRunningWorld());
    }

    @Test
    void testAutoSyncOnStartDisabledBehavior() {
        CloudSyncManager manager = CloudSyncManager.getInstance();
        SyncConfig config = SyncConfig.load();
        config.autoSyncOnStart = false;
        config.save();

        // When initialSyncCompleted is false and autoSyncOnStart is false, before marking
        assertFalse(manager.isWorldSynchronized("WorldX"));

        // After title screen / markInitialSyncCompleted
        manager.markInitialSyncCompleted();
        assertTrue(manager.isWorldSynchronized("WorldX"));
    }

    @Test
    void testPendingAndSynchronizedSets() {
        CloudSyncManager manager = CloudSyncManager.getInstance();
        String world = "TestSetWorld";

        manager.markWorldUnsynchronized(world);
        assertTrue(manager.getPendingSyncWorlds().contains(world));
        assertFalse(manager.getSynchronizedWorlds().contains(world));

        manager.markWorldSynchronized(world);
        assertFalse(manager.getPendingSyncWorlds().contains(world));
        assertTrue(manager.getSynchronizedWorlds().contains(world));
    }

    @Test
    void testSyncCallbacks() throws IOException {
        CloudSyncManager manager = CloudSyncManager.getInstance();
        java.util.concurrent.atomic.AtomicBoolean batchCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> worldSynced = new java.util.concurrent.atomic.AtomicReference<>();

        manager.setBatchSyncCompleteCallback(() -> batchCalled.set(true));
        manager.setWorldSyncedCallback(worldSynced::set);

        manager.markWorldSynchronized("TestWorld1");
        assertEquals("TestWorld1", worldSynced.get());

        manager.markInitialSyncCompleted();
        assertTrue(batchCalled.get());
    }

    @Test
    void testIsSyncingAll() {
        CloudSyncManager manager = CloudSyncManager.getInstance();
        assertFalse(manager.isSyncingAll());
    }
}
