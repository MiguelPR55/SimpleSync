package dev.simplesync.mixin;

import dev.simplesync.SimpleSync;
import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.WorldSyncTask;
import dev.simplesync.util.SyncLogger;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/**
 * Mixin that triggers cloud sync when the title screen is first shown.
 * Ensures worlds are downloaded/updated from the cloud when the game starts.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        dev.simplesync.ui.SyncConflictScreen pendingConflict = dev.simplesync.SimpleSyncClient.getPendingConflictScreen();
        if (pendingConflict != null) {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client != null && client.gui != null) {
                client.gui.setScreen(pendingConflict);
                return;
            }
        }

        if (SimpleSync.needsTitleScreenSync.compareAndSet(true, false)) {
            SyncConfig config = SyncConfig.load();
            if (config.autoSyncOnStart) {
                SyncLogger.info("[SimpleSync] Title screen opened, starting cloud sync...");
                CloudSyncManager.getInstance().syncAllWorldsFromCloud();
            } else {
                CloudSyncManager.getInstance().markInitialSyncCompleted();
                CompletableFuture.runAsync(() -> {
                    WorldSyncTask.cleanupOrphanedDirectories(CloudSyncManager.getInstance().getSavesDirectory());
                }, CloudSyncManager.getInstance().getExecutor());
            }
        }
    }
}
