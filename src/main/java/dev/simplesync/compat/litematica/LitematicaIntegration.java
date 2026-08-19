package dev.simplesync.compat.litematica;

import dev.simplesync.util.SyncLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Bridge layer for Litematica mod integration.
 * Designed with a soft-dependency approach to ensure zero classloading crashes
 * when Litematica is not present in the environment.
 */
public class LitematicaIntegration {

    public static final String MOD_ID = "litematica";
    private static Boolean litematicaAvailable = null;

    /**
     * Checks whether the Litematica mod is currently loaded in the Fabric runtime.
     */
    public static boolean isLitematicaAvailable() {
        if (litematicaAvailable == null) {
            try {
                litematicaAvailable = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(MOD_ID);
            } catch (Throwable ignored) {
                litematicaAvailable = false;
            }
        }
        return litematicaAvailable;
    }

    /**
     * Called when exiting a world to ensure Litematica placements and configurations are flushed and ready for cloud sync.
     *
     * @param worldName    The name/id of the world that just closed.
     * @param gameRootDir  The Minecraft root directory (e.g. .minecraft).
     */
    public static void onWorldExit(String worldName, Path gameRootDir) {
        if (worldName == null || gameRootDir == null || !isLitematicaAvailable()) return;
        try {
            prepareWorldPlacements(worldName, gameRootDir);
            SyncLogger.info("[SimpleSync] Litematica world-exit hooks processed for: {}", worldName);
        } catch (Exception e) {
            SyncLogger.warn("[SimpleSync] Error during Litematica world-exit handler: {}", e.getMessage());
        }
    }

    /**
     * Prepares and auto-heals all Litematica placement files for a specific world before the player enters it.
     * Guarantees that all .litematic paths point to valid local files before Litematica reads the JSON on dimension load.
     *
     * @param worldName    The level id / world name to prepare.
     * @param gameRootDir  The Minecraft game root directory.
     */
    public static void prepareWorldPlacements(String worldName, Path gameRootDir) {
        if (worldName == null || gameRootDir == null || !isLitematicaAvailable()) return;
        Path litematicaConfigDir = gameRootDir.resolve("config").resolve("litematica");
        if (!Files.isDirectory(litematicaConfigDir)) return;

        Path schematicsDir = gameRootDir.resolve("schematics");
        autoHealWorldPlacements(worldName, litematicaConfigDir, schematicsDir);
    }

    /**
     * Scans and auto-heals any Litematica config files associated with the specified world name.
     */
    private static void autoHealWorldPlacements(String worldName, Path litematicaConfigDir, Path schematicsDir) {
        if (!Files.isDirectory(litematicaConfigDir)) return;
        String worldLower = worldName != null ? worldName.toLowerCase(Locale.ROOT) : "";
        try (var stream = Files.walk(litematicaConfigDir, 3)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName() != null && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                  .filter(p -> {
                      String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                      return worldLower.isEmpty() || fn.contains(worldLower) || fn.contains("_dim_");
                  })
                  .forEach(p -> LitematicaPathNormalizer.autoHealFile(p, schematicsDir));
        } catch (IOException e) {
            SyncLogger.warn("[SimpleSync] Failed to scan Litematica placement configs for world {}: {}", worldName, e.getMessage());
        }
    }
}
