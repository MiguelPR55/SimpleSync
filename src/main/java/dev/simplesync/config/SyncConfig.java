package dev.simplesync.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.simplesync.SimpleSync;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Persistent configuration for SimpleSync.
 * Stored as JSON in the config/simplesync/ directory.
 */
public class SyncConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "config.json";
    private static final Object FILE_LOCK = new Object();
    private static volatile Path configDir;
    private static SyncConfig INSTANCE;

    // Configuration fields
    public boolean autoSyncOnStart = true;
    public boolean autoSyncOnExit = true;
    public boolean syncSchematics = true;
    public boolean syncMasaConfigs = true;
    public String cloudProvider = "google_drive";
    public Map<String, WorldTrackingInfo> worldTracking = new java.util.concurrent.ConcurrentHashMap<>();
    public Map<String, FileTrackingInfo> fileTracking = new java.util.concurrent.ConcurrentHashMap<>();
    public java.util.Set<String> ignoredCloudWorlds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    public String simpleSyncFolderId;
    public String worldsFolderId;
    public String schematicsFolderId;
    public String configsFolderId;

    // Legacy fields for backwards-compatibility migration during deserialization
    private Map<String, Long> lastSyncTimestamps;
    private Map<String, Long> lastLocalSizes;
    private Map<String, Long> lastLocalMtimes;

    public record WorldTrackingInfo(long lastSyncTimestamp, long lastLocalSize, long lastLocalMtime) {}
    public record FileTrackingInfo(long lastSyncTimestamp, long lastLocalSize, long lastLocalMtime) {}

    /**
     * Gets the config directory path, creating it if necessary.
     *
     * @return The path to the config directory.
     */
    public static Path getConfigDir() {
        Path dir = configDir;
        if (dir == null) {
            dir = Path.of("config", "simplesync");
            configDir = dir;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            SimpleSync.LOGGER.error("[SimpleSync] Failed to create config directory", e);
        }
        return dir;
    }

    /**
     * Loads the configuration from disk, or creates a new default config.
     *
     * @return The loaded or newly created SyncConfig.
     */
    public static SyncConfig load() {
        synchronized (FILE_LOCK) {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            Path configFile = getConfigDir().resolve(CONFIG_FILE);
            Path tempFile = getConfigDir().resolve(CONFIG_FILE + ".tmp");

            SyncConfig config = tryLoadFile(configFile);
            if (config == null && Files.exists(tempFile)) {
                SimpleSync.LOGGER.warn("[SimpleSync] Main config failed to load or missing, attempting to recover from temp config file...");
                config = tryLoadFile(tempFile);
            }

            if (config != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Failed to delete leftover temp config file: {}", e.getMessage());
                }
                INSTANCE = config;
                return INSTANCE;
            }

            if (Files.exists(configFile)) {
                try {
                    Path corruptFile = getConfigDir().resolve(CONFIG_FILE + ".corrupted");
                    Files.move(configFile, corruptFile, StandardCopyOption.REPLACE_EXISTING);
                    SimpleSync.LOGGER.error("[SimpleSync] Config file was corrupted and has been backed up to: {}", corruptFile);
                } catch (IOException e) {
                    SimpleSync.LOGGER.error("[SimpleSync] Failed to back up corrupted config file", e);
                }
            }

            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {}

            config = new SyncConfig();
            config.save();
            INSTANCE = config;
            return INSTANCE;
        }
    }

    /**
     * Resets the cached singleton instance, forcing a reload from disk on next load().
     * Useful for testing or recovery.
     */
    public static void resetInstance() {
        synchronized (FILE_LOCK) {
            INSTANCE = null;
        }
    }

    private static SyncConfig tryLoadFile(Path file) {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) == 0) {
                return null;
            }
            String json = Files.readString(file);
            SyncConfig config = GSON.fromJson(json, SyncConfig.class);
            if (config != null) {
                if (config.worldTracking == null) {
                    config.worldTracking = new java.util.concurrent.ConcurrentHashMap<>();
                }
                if (config.fileTracking == null) {
                    config.fileTracking = new java.util.concurrent.ConcurrentHashMap<>();
                }
                if (config.lastSyncTimestamps != null || config.lastLocalSizes != null || config.lastLocalMtimes != null) {
                    java.util.Set<String> allWorlds = new java.util.HashSet<>();
                    if (config.lastSyncTimestamps != null) allWorlds.addAll(config.lastSyncTimestamps.keySet());
                    if (config.lastLocalSizes != null) allWorlds.addAll(config.lastLocalSizes.keySet());
                    if (config.lastLocalMtimes != null) allWorlds.addAll(config.lastLocalMtimes.keySet());
                    for (String w : allWorlds) {
                        long ts = config.lastSyncTimestamps != null ? config.lastSyncTimestamps.getOrDefault(w, 0L) : 0L;
                        long sz = config.lastLocalSizes != null ? config.lastLocalSizes.getOrDefault(w, 0L) : 0L;
                        long mt = config.lastLocalMtimes != null ? config.lastLocalMtimes.getOrDefault(w, 0L) : 0L;
                        config.worldTracking.put(w, new WorldTrackingInfo(ts, sz, mt));
                    }
                    config.lastSyncTimestamps = null;
                    config.lastLocalSizes = null;
                    config.lastLocalMtimes = null;
                }
                if (config.ignoredCloudWorlds == null) {
                    config.ignoredCloudWorlds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
                } else {
                    java.util.Set<String> ignored = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
                    ignored.addAll(config.ignoredCloudWorlds);
                    config.ignoredCloudWorlds = ignored;
                }
                return config;
            }
        } catch (Exception e) {
            SimpleSync.LOGGER.error("[SimpleSync] Failed to load config from {}", file, e);
        }
        return null;
    }

    /**
     * Saves the configuration to disk.
     */
    public void save() {
        synchronized (FILE_LOCK) {
            Path configFile = getConfigDir().resolve(CONFIG_FILE);
            Path tempFile = getConfigDir().resolve(CONFIG_FILE + ".tmp");

            try {
                Files.createDirectories(configFile.getParent());
                String json = GSON.toJson(this);
                Files.writeString(tempFile, json);
                try {
                    Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                SimpleSync.LOGGER.error("[SimpleSync] Failed to save config", e);
            }
        }
    }

    public WorldTrackingInfo getTracking(String worldName) {
        return worldTracking.getOrDefault(worldName, new WorldTrackingInfo(0L, 0L, 0L));
    }

    public void setTracking(String worldName, WorldTrackingInfo info) {
        worldTracking.put(worldName, info);
    }

    public void removeTracking(String worldName) {
        worldTracking.remove(worldName);
    }

    public FileTrackingInfo getFileTracking(String relativePath) {
        if (fileTracking == null) {
            fileTracking = new java.util.concurrent.ConcurrentHashMap<>();
        }
        return fileTracking.getOrDefault(relativePath, new FileTrackingInfo(0L, 0L, 0L));
    }

    public void setFileTracking(String relativePath, FileTrackingInfo info) {
        if (fileTracking == null) {
            fileTracking = new java.util.concurrent.ConcurrentHashMap<>();
        }
        fileTracking.put(relativePath, info);
    }

    public void removeFileTracking(String relativePath) {
        if (fileTracking != null) {
            fileTracking.remove(relativePath);
        }
    }

}
