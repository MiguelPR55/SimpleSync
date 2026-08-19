package dev.simplesync;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.WorldSyncTask;
import dev.simplesync.ui.DeviceAuthScreen;
import dev.simplesync.ui.ReloadableScreen;
import dev.simplesync.ui.SyncConflictScreen;
import dev.simplesync.ui.SyncStatusOverlay;
import dev.simplesync.util.SyncLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class SimpleSyncClient implements ClientModInitializer {

    private static volatile SyncConflictScreen pendingConflictScreen = null;
    private static volatile long lastScreenReloadTime = 0;

    public static SyncConflictScreen getPendingConflictScreen() {
        return pendingConflictScreen;
    }

    public static void clearPendingConflictScreen() {
        pendingConflictScreen = null;
    }

    @Override
    public void onInitializeClient() {
        SyncLogger.info("[SimpleSync] Initializing client...");
        preloadClientClasses();
        
        // Resolve saves directory early using FabricLoader if available, with Minecraft fallback
        Path savesDir;
        try {
            savesDir = FabricLoader.getInstance().getGameDir().resolve("saves");
        } catch (Throwable t) {
            Minecraft client = Minecraft.getInstance();
            savesDir = (client != null && client.gameDirectory != null)
                    ? client.gameDirectory.toPath().resolve("saves")
                    : Path.of("saves");
        }
        CloudSyncManager.getInstance().setSavesDirectory(savesDir);

        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath("simplesync", "sync_status"),
            (extractor, deltaTracker) -> SyncStatusOverlay.getInstance().renderOverlay(extractor)
        );

        CloudSyncManager.getInstance().setConflictCallback((worldName, localTs, cloudTs, onUseCloud, onKeepLocal) -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            client.execute(() -> {
                SyncConflictScreen conflictScreen = new SyncConflictScreen(
                        worldName, localTs, cloudTs,
                        () -> {
                            pendingConflictScreen = null;
                            onUseCloud.run();
                        },
                        () -> {
                            pendingConflictScreen = null;
                            onKeepLocal.run();
                        }
                );
                pendingConflictScreen = conflictScreen;
                if (client.gui != null) {
                    client.gui.setScreen(conflictScreen);
                }
            });
        });

        CloudSyncManager.getInstance().setConflictCancelCallback(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            client.execute(() -> {
                pendingConflictScreen = null;
                if (client.gui != null && client.gui.screen() instanceof SyncConflictScreen screen) {
                    screen.onClose();
                }
            });
        });

        CloudSyncManager.getInstance().setAuthPromptCallback((userCode, verificationUrl, expiresInSeconds, onCancel) -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            client.execute(() -> {
                if (client.gui != null) {
                    net.minecraft.client.gui.screens.Screen current = client.gui.screen();
                    client.gui.setScreen(new DeviceAuthScreen(
                            current, userCode, verificationUrl, expiresInSeconds, onCancel
                    ));
                }
            });
        });

        // Automatically refresh Singleplayer screen if the user is currently viewing it when sync completes
        CloudSyncManager.getInstance().setWorldSyncedCallback(worldName -> triggerScreenReload(false));
        CloudSyncManager.getInstance().setBatchSyncCompleteCallback(() -> triggerScreenReload(true));

        // Early Startup Sync: Start background cloud sync immediately during client initialization
        // so it runs concurrently while Minecraft is loading assets, textures, and models.
        SyncConfig config = SyncConfig.load();
        if (config.autoSyncOnStart) {
            if (CloudSyncManager.getInstance().getProvider().isAuthenticated()) {
                SyncLogger.info("[SimpleSync] Starting early background cloud sync during game launch...");
                SimpleSync.needsTitleScreenSync.set(false);
                CloudSyncManager.getInstance().syncAllWorldsFromCloud();
            } else {
                SyncLogger.info("[SimpleSync] Auto-sync on start is enabled, but provider is not authenticated. Deferring auth prompt to Title Screen.");
            }
        } else {
            SimpleSync.needsTitleScreenSync.set(false);
            CloudSyncManager.getInstance().markInitialSyncCompleted();
            CompletableFuture.runAsync(() -> {
                WorldSyncTask.cleanupOrphanedDirectories(CloudSyncManager.getInstance().getSavesDirectory());
            }, CloudSyncManager.getInstance().getExecutor());
        }
    }

    private static void triggerScreenReload(boolean force) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            long now = System.currentTimeMillis();
            if (!force && now - lastScreenReloadTime < 250) {
                return;
            }
            lastScreenReloadTime = now;
            if (client.gui != null && client.gui.screen() instanceof ReloadableScreen reloadable) {
                reloadable.reloadAndReturn();
            }
        });
    }

    private static void preloadClientClasses() {
        String[] classes = {
            "dev.simplesync.ui.DeviceAuthScreen",
            "dev.simplesync.ui.SyncConfigScreen",
            "dev.simplesync.ui.SyncConflictScreen",
            "dev.simplesync.ui.SyncStatusOverlay"
        };
        for (String cls : classes) {
            try {
                Class.forName(cls, true, SimpleSyncClient.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                SyncLogger.warn("[SimpleSync] Preloading failed for client class: {}", cls);
            }
        }
    }
}
