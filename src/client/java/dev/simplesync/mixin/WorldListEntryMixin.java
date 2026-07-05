package dev.simplesync.mixin;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.SyncStatus;
import dev.simplesync.ui.DeleteWorldConfirmScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that prevents entering a world while sync is active, and intercepts world deletion to handle Google Drive cleanup.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry")
public abstract class WorldListEntryMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private LevelSummary summary;
    @Shadow @Final private WorldSelectionList list;
    @Shadow public abstract void doDeleteWorld();

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void onJoinWorld(CallbackInfo ci) {
        SyncStatus status = CloudSyncManager.getInstance().getStatus();
        if (status.isBusy()) {
            dev.simplesync.SimpleSync.LOGGER.warn("[SimpleSync] Prevented joining world because cloud sync is active: {}", status);
            ci.cancel();
        }
    }

    @Inject(method = "deleteWorld", at = @At("HEAD"), cancellable = true)
    private void onDeleteWorld(CallbackInfo ci) {
        SyncStatus status = CloudSyncManager.getInstance().getStatus();
        if (status.isBusy()) {
            dev.simplesync.SimpleSync.LOGGER.warn("[SimpleSync] Prevented deleting world because cloud sync is active: {}", status);
            ci.cancel();
            return;
        }
        String worldId = this.summary.getLevelId();
        String worldName = this.summary.getLevelName();

        this.minecraft.gui.setScreen(new DeleteWorldConfirmScreen(
                (result, deleteFromDrive) -> {
                    if (result) {
                        this.minecraft.gui.setScreen(new ProgressScreen(true));
                        this.doDeleteWorld();

                        if (deleteFromDrive) {
                            CloudSyncManager.getInstance().deleteWorldFromCloudAsync(worldId);
                        } else {
                            SyncConfig config = SyncConfig.load();
                            config.removeTracking(worldId);
                            if (config.ignoredCloudWorlds != null) {
                                config.ignoredCloudWorlds.add(worldId);
                            }
                            config.save();
                        }
                    }
                    this.list.returnToScreen();
                },
                Component.translatable("selectWorld.deleteQuestion"),
                Component.translatable("selectWorld.deleteWarning", worldName),
                Component.translatable("selectWorld.deleteButton"),
                CommonComponents.GUI_CANCEL
        ));
        ci.cancel();
    }
}
