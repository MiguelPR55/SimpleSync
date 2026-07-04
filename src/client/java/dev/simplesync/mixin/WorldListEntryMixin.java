package dev.simplesync.mixin;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.SyncStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that prevents the player from entering a world while cloud sync is active.
 * Protects world files from corruption or race conditions.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry")
public class WorldListEntryMixin {

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void onJoinWorld(CallbackInfo ci) {
        SyncStatus status = CloudSyncManager.getInstance().getStatus();
        if (status == SyncStatus.DOWNLOADING || status == SyncStatus.EXTRACTING || status == SyncStatus.COMPRESSING || status == SyncStatus.UPLOADING || status == SyncStatus.CHECKING || status == SyncStatus.CONFLICT) {
            dev.simplesync.SimpleSync.LOGGER.warn("[SimpleSync] Prevented joining world because cloud sync is active: {}", status);
            ci.cancel();
        }
    }
}
