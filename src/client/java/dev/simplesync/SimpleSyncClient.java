package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.ui.SyncStatusOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

public class SimpleSyncClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SimpleSync.LOGGER.info("[SimpleSync] Initializing client...");
        
        // Pass the actual Minecraft runDirectory to CloudSyncManager
        CloudSyncManager.getInstance().setSavesDirectory(
            MinecraftClient.getInstance().runDirectory.toPath().resolve("saves")
        );

        HudRenderCallback.EVENT.register(SyncStatusOverlay.getInstance());
    }
}
