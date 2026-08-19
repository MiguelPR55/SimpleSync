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
            savesDir = Minecraft.getInstance().gameDirectory.toPath().resolve("saves");
        }
        CloudSyncManager.getInstance().setSavesDirectory(savesDir);

        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath("simplesync", "sync_status"),
            (extractor, deltaTracker) -> SyncStatusOverlay.getInstance().renderOverlay(extractor)
        );

        CloudSyncManager.getInstance().setConflictCallback((worldName, localTs, cloudTs, onUseCloud, onKeepLocal) -> {
            Minecraft.getInstance().execute(() -> {
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
                if (Minecraft.getInstance().gui != null) {
                    Minecraft.getInstance().gui.setScreen(conflictScreen);
                }
            });
        });

        CloudSyncManager.getInstance().setConflictCancelCallback(() -> {
            Minecraft.getInstance().execute(() -> {
                pendingConflictScreen = null;
                if (Minecraft.getInstance().gui != null && Minecraft.getInstance().gui.screen() instanceof SyncConflictScreen screen) {
                    screen.onClose();
                }
            });
        });

        CloudSyncManager.getInstance().setAuthPromptCallback((userCode, verificationUrl, expiresInSeconds, onCancel) -> {
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().gui != null) {
                    net.minecraft.client.gui.screens.Screen current = Minecraft.getInstance().gui.screen();
                    Minecraft.getInstance().gui.setScreen(new DeviceAuthScreen(
                            current, userCode, verificationUrl, expiresInSeconds, onCancel
                    ));
                }
            });
        });

        // Automatically refresh Singleplayer screen if the user is currently viewing it when sync completes
        CloudSyncManager.getInstance().setWorldSyncedCallback(worldName -> {
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().gui != null && Minecraft.getInstance().gui.screen() instanceof ReloadableScreen reloadable) {
                    reloadable.reloadAndReturn();
                }
            });
        });

        CloudSyncManager.getInstance().setBatchSyncCompleteCallback(() -> {
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().gui != null && Minecraft.getInstance().gui.screen() instanceof ReloadableScreen reloadable) {
                    reloadable.reloadAndReturn();
                }
            });
        });

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
