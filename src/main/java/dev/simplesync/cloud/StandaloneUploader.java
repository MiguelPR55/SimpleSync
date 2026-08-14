package dev.simplesync.cloud;

import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.WorldMetadata;
import dev.simplesync.sync.WorldSyncTask;
import dev.simplesync.util.SyncLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone entry point for uploading worlds in a detached background process
 * after the main Minecraft client window has closed.
 */
public class StandaloneUploader {

    public static void main(String[] args) {
        System.setProperty("simplesync.standalone", "true");

        String worldName = null;
        Path worldDir = null;
        Path archivePath = null;
        Path configDir = null;

        for (int i = 0; i < args.length; i++) {
            if ("--world".equals(args[i]) && i + 1 < args.length) {
                worldName = args[++i];
            } else if ("--worldDir".equals(args[i]) && i + 1 < args.length) {
                worldDir = Paths.get(args[++i]);
            } else if ("--archive".equals(args[i]) && i + 1 < args.length) {
                archivePath = Paths.get(args[++i]);
            } else if ("--config".equals(args[i]) && i + 1 < args.length) {
                configDir = Paths.get(args[++i]);
            }
        }

        if (worldName == null || configDir == null) {
            SyncLogger.error("[SimpleSync-Uploader] Missing required parameters.");
            System.exit(1);
            return;
        }

        SyncLogger.info("[SimpleSync-Uploader] Starting detached background upload for world: {}", worldName);
        Path targetArchive = archivePath != null ? archivePath : configDir.resolve("temp").resolve(worldName + ".tar.zst");

        CloudProvider provider = null;
        try {
            SyncConfig.setConfigDir(configDir);
            SyncConfig config = SyncConfig.load();

            // If archive doesn't exist yet, compress the world directory now
            if (!Files.exists(targetArchive) && worldDir != null && Files.isDirectory(worldDir)) {
                SyncLogger.info("[SimpleSync-Uploader] Compressing world directory: {}", worldDir);
                Files.createDirectories(targetArchive.getParent());
                WorldSyncTask.compressWorld(worldDir, targetArchive);
            }

            if (!Files.exists(targetArchive)) {
                SyncLogger.error("[SimpleSync-Uploader] Archive file not found: {}", targetArchive);
                System.exit(1);
                return;
            }

            provider = CloudProviderFactory.create(config.cloudProvider);
            SyncLogger.info("[SimpleSync-Uploader] Uploading archive using {}...", provider.getName());
            WorldMetadata uploaded = provider.upload(worldName, targetArchive);

            if (uploaded != null) {
                long newTs = uploaded.lastModified() > 0 ? uploaded.lastModified() : System.currentTimeMillis();
                long size = Files.size(targetArchive);
                config.setTracking(worldName, new SyncConfig.WorldTrackingInfo(newTs, size, newTs));
                config.save();
                SyncLogger.info("[SimpleSync-Uploader] Successfully uploaded '{}' via {}!", worldName, provider.getName());
            }
        } catch (Exception e) {
            SyncLogger.error("[SimpleSync-Uploader] Upload failed: {}", e.getMessage(), e);
        } finally {
            if (provider != null) {
                try { provider.shutdown(); } catch (Exception ignored) {}
            }
            try {
                if (targetArchive != null) Files.deleteIfExists(targetArchive);
            } catch (Exception ignored) {}
        }
    }
}
