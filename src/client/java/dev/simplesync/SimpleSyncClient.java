package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.ui.SyncStatusOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

public class SimpleSyncClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SimpleSync.LOGGER.info("[SimpleSync] Initializing client...");
        
        // Pass the actual Minecraft runDirectory to CloudSyncManager
        CloudSyncManager.getInstance().setSavesDirectory(
            MinecraftClient.getInstance().runDirectory.toPath().resolve("saves")
        );

        HudLayerRegistrationCallback.EVENT.register(layeredDrawer -> layeredDrawer.attachLayerAfter(
            IdentifiedLayer.MISC_OVERLAYS,
            Identifier.of("simplesync", "sync_status"),
            (context, tickCounter) -> SyncStatusOverlay.getInstance().renderOverlay(context)
        ));

        CloudSyncManager.getInstance().setConflictCallback((worldName, localTs, cloudTs, onUseCloud, onKeepLocal) -> {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().setScreen(new dev.simplesync.ui.SyncConflictScreen(
                        worldName, localTs, cloudTs, onUseCloud, onKeepLocal
                ));
            });
        });

        CloudSyncManager.getInstance().setConflictCancelCallback(() -> {
            MinecraftClient.getInstance().execute(() -> {
                if (MinecraftClient.getInstance().currentScreen instanceof dev.simplesync.ui.SyncConflictScreen screen) {
                    screen.close();
                }
            });
        });

        CloudSyncManager.getInstance().setAuthPromptCallback((userCode, verificationUrl, expiresInSeconds, onCancel) -> {
            MinecraftClient.getInstance().execute(() -> {
                net.minecraft.client.gui.screen.Screen current = MinecraftClient.getInstance().currentScreen;
                MinecraftClient.getInstance().setScreen(new dev.simplesync.ui.DeviceAuthScreen(
                        current, userCode, verificationUrl, expiresInSeconds, onCancel
                ));
            });
        });
    }
}
