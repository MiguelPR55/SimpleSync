package dev.simplesync.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to prevent NullPointerException in MultiPlayerGameMode.ensureHasSentCarriedItem
 * when Minecraft.level is non-null during world load/transition but minecraft.player is still null.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "ensureHasSentCarriedItem", at = @At("HEAD"), cancellable = true)
    private void onEnsureHasSentCarriedItem(CallbackInfo ci) {
        if (this.minecraft == null || this.minecraft.player == null) {
            ci.cancel();
        }
    }
}
