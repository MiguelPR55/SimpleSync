package dev.simplesync.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.simplesync.ui.SyncConfigScreen;

/**
 * Mod Menu integration class.
 * Registers dev.simplesync.ui.SyncConfigScreen as the configuration screen for SimpleSync.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SyncConfigScreen::new;
    }
}
