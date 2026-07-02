package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleSync implements ModInitializer {
    public static final String MOD_ID = "simplesync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static String lastWorldName = null;

    @Override
    public void onInitialize() {
        LOGGER.info("[SimpleSync] Initializing...");

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (isIntegratedServer(server)) {
                lastWorldName = server.getSavePath(WorldSavePath.ROOT)
                        .normalize().getFileName().toString();
                LOGGER.info("[SimpleSync] World starting: {}", lastWorldName);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (isIntegratedServer(server) && lastWorldName != null) {
                if (dev.simplesync.config.SyncConfig.load().autoSyncOnExit) {
                    LOGGER.info("[SimpleSync] World stopped: {}. Triggering upload...", lastWorldName);
                    CloudSyncManager.getInstance().uploadWorldAsync(lastWorldName);
                } else {
                    LOGGER.info("[SimpleSync] World stopped: {}, but autoSyncOnExit is disabled. Skipping auto-upload.", lastWorldName);
                }
            }
        });
    }

    private static boolean isIntegratedServer(MinecraftServer server) {
        return !server.isDedicated();
    }

    public static String getLastWorldName() {
        return lastWorldName;
    }
}
