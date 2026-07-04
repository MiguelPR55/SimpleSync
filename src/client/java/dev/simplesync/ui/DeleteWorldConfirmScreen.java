package dev.simplesync.ui;

import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import java.util.function.BiConsumer;

/**
 * Custom delete confirmation screen that includes a checkbox to also delete the world from Google Drive.
 */
public class DeleteWorldConfirmScreen extends ConfirmScreen {

    private Checkbox deleteFromDriveCheckbox;
    private final BiConsumer<Boolean, Boolean> actionCallback;
    private boolean deleteFromDrive = true;

    public DeleteWorldConfirmScreen(BiConsumer<Boolean, Boolean> actionCallback, Component title, Component message, Component yesButton, Component noButton) {
        super(ignored -> {}, title, message, yesButton, noButton);
        this.actionCallback = actionCallback;
        this.deleteFromDrive = true;
    }

    @Override
    protected void addAdditionalText() {
        this.deleteFromDriveCheckbox = Checkbox.builder(
                Component.translatable("simplesync.world.delete_from_drive"),
                this.font
        ).selected(this.deleteFromDrive)
         .onValueChange((cb, val) -> this.deleteFromDrive = val)
         .build();
        this.layout.addChild(this.deleteFromDriveCheckbox);
    }

    @Override
    protected void addButtons(LinearLayout layout) {
        this.yesButton = net.minecraft.client.gui.components.Button.builder(
                this.yesButtonComponent,
                btn -> this.actionCallback.accept(true, this.isDeleteFromDriveSelected())
        ).build();
        layout.addChild(this.yesButton);

        this.noButton = net.minecraft.client.gui.components.Button.builder(
                this.noButtonComponent,
                btn -> this.actionCallback.accept(false, this.isDeleteFromDriveSelected())
        ).build();
        layout.addChild(this.noButton);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            this.actionCallback.accept(false, this.isDeleteFromDriveSelected());
            return true;
        }
        return super.keyPressed(event);
    }

    public boolean isDeleteFromDriveSelected() {
        return this.deleteFromDrive;
    }
}
