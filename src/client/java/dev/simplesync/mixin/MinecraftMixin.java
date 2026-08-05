package dev.simplesync.mixin;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.sync.SyncStatus;
import dev.simplesync.ui.SyncingQuitScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept Minecraft.stop() when a cloud world sync/upload is active.
 * Redirects to SyncingQuitScreen to show live upload progress and allow graceful or instant exit.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void onStop(CallbackInfo ci) {
        SyncStatus status = CloudSyncManager.getInstance().getStatus();
        Minecraft client = (Minecraft) (Object) this;

        if (status.isBusy()) {
            client.execute(() -> {
                if (client.gui != null) {
                    client.gui.setScreen(new SyncingQuitScreen());
                }
            });
            ci.cancel();
        }
    }
}
