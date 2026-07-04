package dev.simplesync.mixin;

import dev.simplesync.ui.CloudWorldsScreen;
import dev.simplesync.ui.ReloadableScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen implements ReloadableScreen {

    @Shadow private WorldSelectionList list;

    protected SelectWorldScreenMixin(Component title) {
        super(title);
    }

    @Override
    public void reloadAndReturn() {
        if (this.list != null) {
            this.list.returnToScreen();
        } else if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen((Screen) (Object) this);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addCloudWorldsButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(
                Component.translatable("simplesync.cloud_worlds.button"),
                button -> {
                    if (this.minecraft != null && this.minecraft.gui != null) {
                        this.minecraft.gui.setScreen(new CloudWorldsScreen((Screen) (Object) this));
                    }
                }
        ).bounds(this.width - 125, 8, 115, 20).build());
    }
}
