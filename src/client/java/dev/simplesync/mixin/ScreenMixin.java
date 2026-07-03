package dev.simplesync.mixin;

import dev.simplesync.ui.SyncStatusOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to render the SimpleSync status overlay on top of any active screen (e.g. TitleScreen, SelectWorldScreen, etc.).
 */
@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        SyncStatusOverlay.getInstance().renderOverlay(context);
    }
}
