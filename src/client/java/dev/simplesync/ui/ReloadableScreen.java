package dev.simplesync.ui;

/**
 * Interface implemented by screens (such as SelectWorldScreen via mixin)
 * that need their list or textures reloaded when returning from a child screen.
 */
public interface ReloadableScreen {
    void reloadAndReturn();
}
