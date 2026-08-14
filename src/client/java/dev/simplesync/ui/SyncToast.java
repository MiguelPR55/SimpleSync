package dev.simplesync.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

public class SyncToast {

    public static void show(Component title, Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.gui != null && client.gui.toastManager() != null) {
            SystemToast.addOrUpdate(
                    client.gui.toastManager(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    title,
                    message
            );
        }
    }

    public static void showWorldSyncingToast(String worldName) {
        show(
                Component.translatable("simplesync.toast.world_syncing"),
                Component.translatable("simplesync.toast.world_syncing_desc", worldName)
        );
    }

    public static void showWorldPendingToast(String worldName) {
        show(
                Component.translatable("simplesync.toast.world_pending"),
                Component.translatable("simplesync.toast.world_pending_desc", worldName)
        );
    }
}
