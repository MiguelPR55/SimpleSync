package dev.simplesync.mixin;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.SyncStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to prevent ClientShutdownWatchdog from force-crashing Minecraft (exit code -8)
 * while SimpleSync is uploading or syncing worlds to Google Drive during game shutdown.
 */
@Mixin(targets = "com.mojang.blaze3d.platform.ClientShutdownWatchdog")
public class ClientShutdownWatchdogMixin {

    @Inject(method = "startShutdownWatchdog", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void onStartShutdownWatchdog(CallbackInfo ci) {
        SyncStatus status = CloudSyncManager.getInstance().getStatus();
        if (status.isBusy()) {
            dev.simplesync.SimpleSync.LOGGER.info("[SimpleSync] Suppressed ClientShutdownWatchdog crash to allow world upload to finish cleanly (Status: {})", status);
            ci.cancel();
        }
    }
}
