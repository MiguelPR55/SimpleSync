package dev.simplesync.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.simplesync.SimpleSync;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistent configuration for SimpleSync.
 * Stored as JSON in the config/simplesync/ directory.
 */
public class SyncConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "config.json";
    private static final Object FILE_LOCK = new Object();
    private static Path configDir;

    // Configuration fields
    public boolean autoSyncOnStart = true;
    public boolean autoSyncOnExit = true;
    public String cloudProvider = "google_drive";
    public Map<String, Long> lastSyncTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    public Map<String, Long> lastLocalSizes = new java.util.concurrent.ConcurrentHashMap<>();
    public Map<String, Long> lastLocalMtimes = new java.util.concurrent.ConcurrentHashMap<>();
    public String simpleSyncFolderId;

    /**
     * Gets the config directory path, creating it if necessary.
     */
    public static Path getConfigDir() {
        if (configDir == null) {
            configDir = Path.of("config", "simplesync");
        }
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            SimpleSync.LOGGER.error("[SimpleSync] Failed to create config directory", e);
        }
        return configDir;
    }

    /**
     * Loads the configuration from disk, or creates a new default config.
     */
    public static SyncConfig load() {
        synchronized (FILE_LOCK) {
            Path configFile = getConfigDir().resolve(CONFIG_FILE);

            if (Files.exists(configFile)) {
                try {
                    String json = Files.readString(configFile);
                    SyncConfig config = GSON.fromJson(json, SyncConfig.class);
                    if (config != null) {
                        config.lastSyncTimestamps = config.lastSyncTimestamps == null ? new java.util.concurrent.ConcurrentHashMap<>() : new java.util.concurrent.ConcurrentHashMap<>(config.lastSyncTimestamps);
                        config.lastLocalSizes = config.lastLocalSizes == null ? new java.util.concurrent.ConcurrentHashMap<>() : new java.util.concurrent.ConcurrentHashMap<>(config.lastLocalSizes);
                        config.lastLocalMtimes = config.lastLocalMtimes == null ? new java.util.concurrent.ConcurrentHashMap<>() : new java.util.concurrent.ConcurrentHashMap<>(config.lastLocalMtimes);
                        return config;
                    }
                } catch (Exception e) {
                    SimpleSync.LOGGER.error("[SimpleSync] Failed to load config, using defaults", e);
                }
            }

            SyncConfig config = new SyncConfig();
            config.save();
            return config;
        }
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

    public long getLastSyncTimestamp(String worldName) {
        return lastSyncTimestamps.getOrDefault(worldName, 0L);
    }

    public void setLastSyncTimestamp(String worldName, long timestamp) {
        lastSyncTimestamps.put(worldName, timestamp);
    }

    public long getLastLocalSize(String worldName) {
        return lastLocalSizes.getOrDefault(worldName, 0L);
    }

    public void setLastLocalSize(String worldName, long size) {
        lastLocalSizes.put(worldName, size);
    }

    public long getLastLocalMtime(String worldName) {
        return lastLocalMtimes.getOrDefault(worldName, 0L);
    }

    public void setLastLocalMtime(String worldName, long mtime) {
        lastLocalMtimes.put(worldName, mtime);
    }

    public String getSimpleSyncFolderId() {
        return simpleSyncFolderId;
    }

    public void setSimpleSyncFolderId(String folderId) {
        this.simpleSyncFolderId = folderId;
    }
}
