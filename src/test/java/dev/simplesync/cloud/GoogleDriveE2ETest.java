package dev.simplesync.cloud;

import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.WorldMetadata;
import dev.simplesync.sync.WorldSyncTask;
import dev.simplesync.sync.ZstdNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GoogleDriveE2ETest {

    private static final Path TEST_B_CONFIG = Path.of(System.getProperty("user.home"), ".local/share/PrismLauncher/instances/SimpleSync-Test-B/minecraft/config/simplesync");
    private static final Path TEST_B_ROOT = Path.of(System.getProperty("user.home"), ".local/share/PrismLauncher/instances/SimpleSync-Test-B/minecraft");
    private static final Path TEST_B_SAVES = TEST_B_ROOT.resolve("saves");
    private static final Path TEST_B_SCHEMATICS = TEST_B_ROOT.resolve("schematics");

    @BeforeAll
    static void setup() {
        SyncConfig.setConfigDir(TEST_B_CONFIG);
        ZstdNativeLoader.ensureLoaded();
    }

    @Test
    void testGoogleDriveSyncEndToEnd() throws Exception {
        if (!Files.exists(TEST_B_CONFIG.resolve("client_secret.json")) || !Files.exists(TEST_B_CONFIG.resolve("credentials/tokens.json"))) {
            System.out.println("Skipping GoogleDriveE2ETest: credentials not configured in Test-B");
            return;
        }

        SyncConfig.setConfigDir(TEST_B_CONFIG);
        SyncConfig config = SyncConfig.load();

        GoogleDriveProvider provider = new GoogleDriveProvider();
        assertTrue(provider.isAuthenticated(), "Provider should be authenticated with tokens in Test-B");

        // If TestSyncWorld does not exist in cloud, compress and upload a minimal test world
        Path tempUpload = TEST_B_CONFIG.resolve("temp/TestSyncWorld-upload.tar.zst");
        Path localTestWorld = Path.of(System.getProperty("user.home"), ".local/share/PrismLauncher/instances/SimpleSync-Test-A/minecraft/saves/TestSyncWorld");
        if (Files.isDirectory(localTestWorld)) {
            Files.createDirectories(tempUpload.getParent());
            WorldSyncTask.compressWorld(localTestWorld, tempUpload);
            provider.upload("TestSyncWorld", tempUpload);
            Files.deleteIfExists(tempUpload);
        }

        List<WorldMetadata> worlds = provider.listWorlds();
        assertNotNull(worlds, "World list should not be null");
        System.out.println("Found " + worlds.size() + " worlds on Google Drive: " + worlds.stream().map(WorldMetadata::worldName).toList());

        boolean hasTestWorld = worlds.stream().anyMatch(w -> "TestSyncWorld".equals(w.worldName()));
        assertTrue(hasTestWorld, "TestSyncWorld should exist on Google Drive");

        // Download and extract TestSyncWorld into Test-B saves
        Path tempArchive = TEST_B_CONFIG.resolve("temp/TestSyncWorld-download.tar.zst");
        Files.createDirectories(tempArchive.getParent());
        try {
            provider.download("TestSyncWorld", tempArchive);
            assertTrue(Files.exists(tempArchive), "Downloaded archive should exist");
            assertTrue(Files.size(tempArchive) > 0, "Downloaded archive should not be empty");

            Path targetWorldFolder = TEST_B_SAVES.resolve("TestSyncWorld");
            WorldSyncTask.extractWorld(tempArchive, targetWorldFolder);

            assertTrue(Files.isDirectory(targetWorldFolder), "Target world folder should exist");
            assertTrue(Files.exists(targetWorldFolder.resolve("level.dat")), "level.dat should exist in extracted world");
            System.out.println("Successfully downloaded and extracted TestSyncWorld into Test-B saves!");
        } finally {
            Files.deleteIfExists(tempArchive);
        }

        // Test Schematics Sync into Test-B
        provider.syncSchematics(TEST_B_ROOT);
        assertTrue(Files.isDirectory(TEST_B_SCHEMATICS), "Schematics folder should exist in Test-B");
        System.out.println("Successfully synced schematics to Test-B!");

        // Test Masa Configs Sync into Test-B
        provider.syncMasaConfigs(TEST_B_ROOT);
        assertTrue(Files.isDirectory(TEST_B_ROOT.resolve("config")), "Config folder should exist in Test-B");
        System.out.println("Successfully synced Masa configs to Test-B!");

        // ─── Test Cross-Instance Modification & Sync ───────────────────────
        System.out.println("Testing cross-instance modification sync...");
        Path markerA = localTestWorld.resolve("sync_test_marker.txt");
        String markerContent = "SimpleSync-Verified-" + System.currentTimeMillis();
        Files.writeString(markerA, markerContent);

        // Upload modified world from Instance A
        Path tempUpload2 = TEST_B_CONFIG.resolve("temp/TestSyncWorld-upload2.tar.zst");
        WorldSyncTask.compressWorld(localTestWorld, tempUpload2);
        provider.upload("TestSyncWorld", tempUpload2);
        Files.deleteIfExists(tempUpload2);

        // Download into Instance B and verify updated marker
        Path targetWorldFolder = TEST_B_SAVES.resolve("TestSyncWorld");
        Path tempArchive2 = TEST_B_CONFIG.resolve("temp/TestSyncWorld-download2.tar.zst");
        provider.download("TestSyncWorld", tempArchive2);
        WorldSyncTask.extractWorld(tempArchive2, targetWorldFolder);
        Files.deleteIfExists(tempArchive2);

        Path markerB = targetWorldFolder.resolve("sync_test_marker.txt");
        assertTrue(Files.exists(markerB), "Marker file should exist in Test-B after download");
        assertEquals(markerContent, Files.readString(markerB), "Marker content in Test-B should match Test-A");
        System.out.println("Cross-instance modification sync verified successfully!");

        // ─── Test Litematica Path Normalization & Auto-Healing ─────────────
        System.out.println("Testing Litematica schematic path auto-healing...");
        String sampleWindowsJson = "{\"schematicPlacement\":{\"file_path\":\"C:\\\\Users\\\\Player\\\\AppData\\\\Roaming\\\\.minecraft\\\\schematics\\\\farm.litematic\"}}";
        String portable = dev.simplesync.compat.litematica.LitematicaPathNormalizer.toPortableJson(sampleWindowsJson, TEST_B_SCHEMATICS, TEST_B_ROOT);
        assertTrue(portable.contains("__SCHEMATICS__/farm.litematic") || portable.contains("__SCHEMATICS__\\\\farm.litematic"), "Path should be portableized to __SCHEMATICS__");

        String localized = dev.simplesync.compat.litematica.LitematicaPathNormalizer.toLocalJson(portable, TEST_B_SCHEMATICS, TEST_B_ROOT);
        assertTrue(localized.contains(TEST_B_SCHEMATICS.toAbsolutePath().toString()), "Path should be localized to Test-B schematics directory");
        System.out.println("Litematica path auto-healing verified successfully!");

        // Clean up TestSyncWorld from Google Drive
        provider.delete("TestSyncWorld");
        List<WorldMetadata> remainingWorlds = provider.listWorlds();
        assertFalse(remainingWorlds.stream().anyMatch(w -> "TestSyncWorld".equals(w.worldName())), "TestSyncWorld should be deleted from Google Drive");
        System.out.println("Successfully deleted TestSyncWorld from Google Drive!");
    }
}
