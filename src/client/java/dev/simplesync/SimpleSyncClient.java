package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.ui.SyncStatusOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class SimpleSyncClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SimpleSync.LOGGER.info("[SimpleSync] Initializing client...");
        
        // Pass the actual Minecraft runDirectory to CloudSyncManager
        CloudSyncManager.getInstance().setSavesDirectory(
            Minecraft.getInstance().gameDirectory.toPath().resolve("saves")
        );

        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath("simplesync", "sync_status"),
            (extractor, deltaTracker) -> SyncStatusOverlay.getInstance().renderOverlay(extractor)
        );

        CloudSyncManager.getInstance().setConflictCallback((worldName, localTs, cloudTs, onUseCloud, onKeepLocal) -> {
            Minecraft.getInstance().execute(() -> {
                Minecraft.getInstance().gui.setScreen(new dev.simplesync.ui.SyncConflictScreen(
                        worldName, localTs, cloudTs, onUseCloud, onKeepLocal
                ));
            });
        });

        CloudSyncManager.getInstance().setConflictCancelCallback(() -> {
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().gui.screen() instanceof dev.simplesync.ui.SyncConflictScreen screen) {
                    screen.onClose();
                }
            });
        });

        CloudSyncManager.getInstance().setAuthPromptCallback((userCode, verificationUrl, expiresInSeconds, onCancel) -> {
            Minecraft.getInstance().execute(() -> {
                net.minecraft.client.gui.screens.Screen current = Minecraft.getInstance().gui.screen();
                Minecraft.getInstance().gui.setScreen(new dev.simplesync.ui.DeviceAuthScreen(
                        current, userCode, verificationUrl, expiresInSeconds, onCancel
                ));
            });
        });
    }
}
