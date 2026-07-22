package dev.simplesync.mixin;

import dev.simplesync.SimpleSync;
import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that triggers cloud sync when the title screen is first shown.
 * Ensures worlds are downloaded/updated from the cloud when the game starts.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (SimpleSync.needsTitleScreenSync) {
            SimpleSync.needsTitleScreenSync = false;
            SyncConfig config = SyncConfig.load();
            if (config.autoSyncOnStart) {
                SimpleSync.LOGGER.info("[SimpleSync] Title screen opened, starting cloud sync...");
                CloudSyncManager.getInstance().syncAllWorldsFromCloud();
            } else {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    dev.simplesync.sync.WorldSyncTask.cleanupOrphanedDirectories(CloudSyncManager.getInstance().getSavesDirectory());
                }, CloudSyncManager.getInstance().getExecutor());
            }
        }
    }
}
