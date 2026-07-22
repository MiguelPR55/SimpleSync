package dev.simplesync.sync;

import dev.simplesync.SimpleSync;

import java.io.IOException;
import java.nio.file.*;

/**
 * Cleans up orphaned _staging, _backup directories and .syncing markers
 * left behind by abnormal shutdowns.
 */
public final class OrphanCleaner {

    private static final String SUFFIX_STAGING = "_staging";
    private static final String SUFFIX_BACKUP = "_backup";
    private static final String SUFFIX_SYNCING = ".syncing";

    private OrphanCleaner() {}

    public static void cleanup(Path savesDir) {
        if (!Files.isDirectory(savesDir)) return;
        cleanupInterruptedSyncs(savesDir);
        cleanupOrphanedDirs(savesDir);
    }

    private static void cleanupInterruptedSyncs(Path savesDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) continue;
                String name = entry.getFileName().toString();
                if (!name.endsWith(SUFFIX_SYNCING)) continue;

                String worldName = name.substring(0, name.length() - SUFFIX_SYNCING.length());
                if (!WorldNameValidator.isWorldNameSafe(worldName)) {
                    try { Files.deleteIfExists(entry); } catch (IOException ignored) {}
                    continue;
                }

                Path originalWorld = savesDir.resolve(worldName);
                Path backupDir = savesDir.resolve(worldName + SUFFIX_BACKUP);
                Path stagingDir = savesDir.resolve(worldName + SUFFIX_STAGING);

                SimpleSync.LOGGER.warn("[SimpleSync] Interrupted sync detected for '{}'", worldName);
                try {
                    if (Files.isDirectory(backupDir)) {
                        if (Files.isDirectory(originalWorld)) WorldArchiver.deleteRecursively(originalWorld);
                        Files.move(backupDir, originalWorld);
                    }
                    if (Files.isDirectory(stagingDir)) WorldArchiver.deleteRecursively(stagingDir);
                } catch (IOException e) {
                    SimpleSync.LOGGER.error("[SimpleSync] Failed to recover world '{}'", worldName, e);
                } finally {
                    try { Files.deleteIfExists(entry); } catch (IOException ignored) {}
                }
            }
        } catch (IOException e) {
            SimpleSync.LOGGER.error("[SimpleSync] Error during interrupted sync check", e);
        }
    }

    private static void cleanupOrphanedDirs(Path savesDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path entry : stream) {
                if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry)) continue;
                String name = entry.getFileName().toString();

                if (name.endsWith(SUFFIX_STAGING)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Cleaning orphaned staging: {}", entry);
                    try { WorldArchiver.deleteRecursively(entry); }
                    catch (IOException e) { SimpleSync.LOGGER.error("[SimpleSync] Failed to delete staging: {}", entry, e); }

                } else if (name.endsWith(SUFFIX_BACKUP)) {
                    String originalName = name.substring(0, name.length() - SUFFIX_BACKUP.length());
                    Path originalWorld = savesDir.resolve(originalName);
                    if (!Files.exists(originalWorld)) {
                        SimpleSync.LOGGER.warn("[SimpleSync] Restoring orphaned backup: {} -> {}", entry, originalWorld);
                        try { Files.move(entry, originalWorld); }
                        catch (IOException e) { SimpleSync.LOGGER.error("[SimpleSync] Failed to restore backup: {}", entry, e); }
                    } else {
                        try { WorldArchiver.deleteRecursively(entry); }
                        catch (IOException e) { SimpleSync.LOGGER.error("[SimpleSync] Failed to delete backup: {}", entry, e); }
                    }
                }
            }
        } catch (IOException e) {
            SimpleSync.LOGGER.error("[SimpleSync] Error during orphan cleanup", e);
        }
    }
}
