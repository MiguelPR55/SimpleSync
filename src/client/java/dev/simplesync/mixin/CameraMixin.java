package dev.simplesync.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to prevent NullPointerException in Camera.getCameraEntityPartialTicks
 * when Minecraft.level is non-null during world load/transition but mainCamera.level or entity is still null.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow private Level level;
    @Shadow private Entity entity;

    @Inject(method = "getCameraEntityPartialTicks", at = @At("HEAD"), cancellable = true)
    private void onGetCameraEntityPartialTicks(DeltaTracker deltaTracker, CallbackInfoReturnable<Float> cir) {
        if (this.level == null || this.entity == null) {
            cir.setReturnValue(deltaTracker.getGameTimeDeltaPartialTick(true));
        }
    }
}
