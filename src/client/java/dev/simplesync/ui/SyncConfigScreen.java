package dev.simplesync.ui;

import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.cloud.DeviceCodeAuthenticator.AuthCancelledException;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.util.DesktopUtil;
import dev.simplesync.util.SyncLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Modern in-game screen to configure SimpleSync.
 * Allows toggling options, checking connection status, authenticating, and viewing instructions.
 */
public class SyncConfigScreen extends Screen {

    private final Screen parent;
    private final SyncConfig config;
    
    private boolean showingTutorial = false;
    private boolean authenticating = false;
    private boolean authenticated = false;
    private Boolean cachedAuthStatus = null;
    private String authError = null;

    public SyncConfigScreen(Screen parent) {
        super(Component.translatable("simplesync.config.title"));
        this.parent = parent;
        this.config = SyncConfig.load();
    }

    @Override
    protected void init() {
        this.rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        // Clear existing widgets
        this.clearWidgets();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        if (showingTutorial) {
            // Add Back button to return to configuration options
            this.addRenderableWidget(Button.builder(
                    Component.translatable("simplesync.tutorial.back"),
                    button -> {
                        showingTutorial = false;
                        this.rebuildWidgets();
                    })
                    .bounds(centerX - 100, this.height - 45, 200, 20)
                    .build());
            return;
        }

        // --- NORMAL CONFIGURATION WIDGETS ---
        
        // Auto Sync on Start Button
        this.addRenderableWidget(Button.builder(
                getAutoStartText(),
                button -> {
                    config.autoSyncOnStart = !config.autoSyncOnStart;
                    config.save();
                    button.setMessage(getAutoStartText());
                })
                .bounds(centerX - 100, centerY - 75, 200, 18)
                .build());

        // Auto Sync on Exit Button
        this.addRenderableWidget(Button.builder(
                getAutoExitText(),
                button -> {
                    config.autoSyncOnExit = !config.autoSyncOnExit;
                    config.save();
                    button.setMessage(getAutoExitText());
                })
                .bounds(centerX - 100, centerY - 55, 200, 18)
                .build());

        // Sync Schematics Toggle Button
        this.addRenderableWidget(Button.builder(
                getSchematicsText(),
                button -> {
                    config.syncSchematics = !config.syncSchematics;
                    config.save();
                    button.setMessage(getSchematicsText());
                })
                .bounds(centerX - 100, centerY - 35, 200, 18)
                .build());

        // Sync Masa Configs Toggle Button
        this.addRenderableWidget(Button.builder(
                getMasaConfigsText(),
                button -> {
                    config.syncMasaConfigs = !config.syncMasaConfigs;
                    config.save();
                    button.setMessage(getMasaConfigsText());
                })
                .bounds(centerX - 100, centerY - 15, 200, 18)
                .build());

        // Google Drive Account Sync/Authentication Button
        if (cachedAuthStatus == null) {
            cachedAuthStatus = !authenticating && CloudSyncManager.getInstance().getProvider().isAuthenticated();
        }
        authenticated = !authenticating && cachedAuthStatus;
        
        Component authBtnText;
        if (authenticating) {
            authBtnText = Component.translatable("simplesync.config.connecting");
        } else if (authenticated) {
            authBtnText = Component.translatable("simplesync.config.disconnect");
        } else {
            authBtnText = Component.translatable("simplesync.config.connect");
        }

        Button connectBtn = Button.builder(authBtnText, button -> {
            if (authenticated) {
                try {
                    CloudSyncManager.getInstance().getProvider().disconnect();
                    CloudSyncManager.getInstance().resetProvider();
                    cachedAuthStatus = false;
                    authenticated = false;
                    authError = null;
                } catch (IOException e) {
                    SyncLogger.error("[SimpleSync] Failed to disconnect provider", e);
                    authError = e.getMessage();
                }
                this.rebuildWidgets();
            } else {
                startAuthenticationFlow();
            }
        })
        .bounds(centerX - 100, centerY + 5, 200, 18)
        .build();

        connectBtn.active = !authenticating;
        this.addRenderableWidget(connectBtn);

        // Cloud Worlds Manager Button
        this.addRenderableWidget(Button.builder(
                Component.translatable("simplesync.cloud_worlds.button"),
                button -> {
                    if (this.minecraft != null && this.minecraft.gui != null) {
                        this.minecraft.gui.setScreen(new CloudWorldsScreen(this));
                    }
                })
                .bounds(centerX - 100, centerY + 25, 200, 18)
                .build());

        // Manual Sync Buttons (Schematics / Masa Configs)
        this.addRenderableWidget(Button.builder(
                Component.translatable("simplesync.config.manual_sync_schematics"),
                button -> CloudSyncManager.getInstance().syncSchematicsAsync())
                .bounds(centerX - 100, centerY + 45, 98, 18)
                .build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("simplesync.config.manual_sync_configs"),
                button -> CloudSyncManager.getInstance().syncMasaConfigsAsync())
                .bounds(centerX + 2, centerY + 45, 98, 18)
                .build());

        // Help / Setup Tutorial Button
        this.addRenderableWidget(Button.builder(
                Component.translatable("simplesync.config.tutorial_btn"),
                button -> {
                    showingTutorial = true;
                    this.rebuildWidgets();
                })
                .bounds(centerX - 100, centerY + 65, 200, 18)
                .build());

        // Done button to close and return to previous screen
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> this.onClose())
                .bounds(centerX - 100, centerY + 85, 200, 18)
                .build());
    }

    private void startAuthenticationFlow() {
        authenticating = true;
        authError = null;
        this.rebuildWidgets();

        CompletableFuture.runAsync(() -> {
            try {
                CloudSyncManager.getInstance().getProvider().authenticate();
                onAuthenticationComplete(true, null);
            } catch (Exception e) {
                SyncLogger.error("[SimpleSync] Google Drive authentication flow failed", e);
                onAuthenticationComplete(false, e);
            }
        }, CloudSyncManager.getInstance().getExecutor());
    }

    private void onAuthenticationComplete(boolean success, Exception e) {
        if (this.minecraft == null) return;
        this.minecraft.execute(() -> {
            cachedAuthStatus = success;
            authenticating = false;
            authenticated = success;
            if (!success && e != null) {
                if (e instanceof AuthCancelledException) {
                    authError = null;
                } else {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    authError = msg.isEmpty() ? "Unknown authentication error" : msg;
                }
            } else {
                authError = null;
            }
            if (this.minecraft.gui != null && this.minecraft.gui.screen() instanceof DeviceAuthScreen authScreen) {
                if (success) {
                    authScreen.markAuthSucceeded();
                }
                this.minecraft.gui.setScreen(this);
            }
            this.rebuildWidgets();
        });
    }

    private Component getAutoStartText() {
        Component state = Component.translatable(config.autoSyncOnStart ? "options.on" : "options.off");
        return Component.translatable("simplesync.config.auto_start", state);
    }

    private Component getAutoExitText() {
        Component state = Component.translatable(config.autoSyncOnExit ? "options.on" : "options.off");
        return Component.translatable("simplesync.config.auto_exit", state);
    }

    private Component getSchematicsText() {
        Component state = Component.translatable(config.syncSchematics ? "options.on" : "options.off");
        return Component.translatable("simplesync.config.sync_schematics", state);
    }

    private Component getMasaConfigsText() {
        Component state = Component.translatable(config.syncMasaConfigs ? "options.on" : "options.off");
        return Component.translatable("simplesync.config.sync_masa_configs", state);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        if (showingTutorial) {
            // --- DRAW TUTORIAL GUIDE SCREEN ---
            extractor.centeredText(this.font,
                    Component.translatable("simplesync.tutorial.title"), centerX, 20, 0xFFFFFFFF);

            int startY = 38;
            int stepGap = 13;

            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step1"), centerX, startY,                 0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step2"), centerX, startY + stepGap,       0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step3"), centerX, startY + stepGap * 2,   0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step4"), centerX, startY + stepGap * 3,   0xFFFFAA00);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step5"), centerX, startY + stepGap * 4,   0xFF55FF55);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step6"), centerX, startY + stepGap * 5,   0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step7"), centerX, startY + stepGap * 6,   0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step8"), centerX, startY + stepGap * 7,   0xFFDDDDDD);
            return;
        }

        // --- DRAW NORMAL CONFIGURATION OPTIONS SCREEN ---
        extractor.centeredText(this.font,
                this.title, centerX, 8, 0xFFFFFFFF);

        Component statusTextVal;
        int statusColor;

        if (authenticating) {
            statusTextVal = Component.translatable("simplesync.config.connecting");
            statusColor = 0xFFFFD54F; // Yellow
        } else if (authenticated) {
            statusTextVal = Component.translatable("simplesync.config.status.connected");
            statusColor = 0xFF81C784; // Green
        } else {
            statusTextVal = Component.translatable("simplesync.config.status.disconnected");
            statusColor = 0xFFFFD54F; // Yellow
        }

        Component statusText = Component.translatable("simplesync.config.status", statusTextVal);
        extractor.centeredText(this.font, statusText, centerX, 21, statusColor);

        // Draw authentication errors if any (split into multiple centered lines to avoid overlap)
        if (authError != null) {
            Component errText = Component.translatable("simplesync.config.auth_failed", authError);
            int currentY = 32;
            for (FormattedCharSequence line : this.font.split(errText, 300)) {
                extractor.centeredText(this.font, line, centerX, currentY, 0xFFE57373);
                currentY += 9;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (showingTutorial && event.button() == 0) {
            if (isTutorialStepClicked(event, 0, "simplesync.tutorial.step1")) {
                confirmAndOpenUrl(this, "https://console.cloud.google.com/");
                return true;
            }
            if (isTutorialStepClicked(event, 1, "simplesync.tutorial.step2")) {
                confirmAndOpenUrl(this, "https://console.cloud.google.com/apis/library/drive.googleapis.com");
                return true;
            }
            if (isTutorialStepClicked(event, 3, "simplesync.tutorial.step4")
                    || isTutorialStepClicked(event, 4, "simplesync.tutorial.step5")) {
                confirmAndOpenUrl(this, "https://console.cloud.google.com/auth/audience");
                return true;
            }
            if (isTutorialStepClicked(event, 7, "simplesync.tutorial.step8")) {
                DesktopUtil.openFile(SyncConfig.getConfigDir().toFile());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean isTutorialStepClicked(MouseButtonEvent event, int stepIndex, String translationKey) {
        int centerX = this.width / 2;
        int stepY = 38 + stepIndex * 13;
        int stepWidth = this.font.width(Component.translatable(translationKey));
        return event.y() >= stepY - 4 && event.y() <= stepY + 13
                && event.x() >= centerX - stepWidth / 2 && event.x() <= centerX + stepWidth / 2;
    }

    public static void confirmAndOpenUrl(Screen parent, String url) {
        Minecraft.getInstance().gui.setScreen(new ConfirmLinkScreen(confirmed -> {
            if (confirmed) {
                DesktopUtil.openUrl(url);
            }
            Minecraft.getInstance().gui.setScreen(parent);
        }, url, true));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
