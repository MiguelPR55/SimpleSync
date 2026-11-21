package dev.simplesync.sync;

import dev.simplesync.config.SyncConfig;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Facade for world-level operations. Delegates to WorldArchiver, WorldNameValidator, OrphanCleaner.
 * Kept for backwards compatibility with existing callers.
 */
public class WorldSyncTask {

    public record WorldStats(long size, long latestModifiedTime) {}

    // ─── Delegates ────────────────────────────────────────────────────────

    public static boolean isWorldNameSafe(String worldName) {
        return WorldNameValidator.isWorldNameSafe(worldName);
    }

    public static WorldStats compressWorld(Path worldFolder, Path outputArchive) throws IOException {
        return WorldArchiver.compressWorld(worldFolder, outputArchive);
    }

    public static void extractWorld(Path archiveFile, Path worldFolder) throws IOException {
        WorldArchiver.extractWorld(archiveFile, worldFolder);
    }

    public static void cleanupOrphanedDirectories(Path savesDir) {
        OrphanCleaner.cleanup(savesDir);
    }

    public static WorldArchiver.ArchiveFormat detectFormat(Path path) throws IOException {
        return WorldArchiver.detectFormat(path);
    }

    // ─── Stats ────────────────────────────────────────────────────────────

    public static WorldStats getWorldStats(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return new WorldStats(0, 0);
        return WorldArchiver.walkWorld(directory, null, null);
    }

    public static boolean isLocalWorldModified(Path worldFolder, SyncConfig config, String worldName) throws IOException {
        return isLocalWorldModified(worldFolder, config, worldName, getWorldStats(worldFolder));
    }

    public static boolean isLocalWorldModified(Path worldFolder, SyncConfig config, String worldName, WorldStats stats) {
        if (!Files.isDirectory(worldFolder)) return false;
        SyncConfig.WorldTrackingInfo tracking = config.getTracking(worldName);
        if (tracking.lastLocalSize() == 0 && tracking.lastLocalMtime() == 0) return true;
        return stats.size() != tracking.lastLocalSize() || stats.latestModifiedTime() != tracking.lastLocalMtime();
    }

    public static long getDirectorySize(Path directory) throws IOException {
        return getWorldStats(directory).size();
    }

    public static long getLatestModifiedTime(Path directory) throws IOException {
        return getWorldStats(directory).latestModifiedTime();
    }
}
