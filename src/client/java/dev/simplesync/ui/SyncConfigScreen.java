package dev.simplesync.ui;

import dev.simplesync.SimpleSync;
import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

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
                    cachedAuthStatus = false;
                    authenticated = false;
                    authError = null;
                } catch (IOException e) {
                    SimpleSync.LOGGER.error("[SimpleSync] Failed to disconnect provider", e);
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
                button -> this.minecraft.gui.setScreen(new CloudWorldsScreen(this)))
                .bounds(centerX - 100, centerY + 25, 200, 18)
                .build());

        // Manual Sync Buttons (Schematics / Masa Configs)
        this.addRenderableWidget(Button.builder(
                Component.literal("Sync Schematics"),
                button -> CloudSyncManager.getInstance().syncSchematicsAsync())
                .bounds(centerX - 100, centerY + 45, 98, 18)
                .build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Sync Configs"),
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
                SimpleSync.LOGGER.error("[SimpleSync] Google Drive authentication flow failed", e);
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
                if (e instanceof dev.simplesync.cloud.DeviceCodeAuthenticator.AuthCancelledException) {
                    authError = null;
                } else {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    authError = msg.isEmpty() ? "Unknown authentication error" : msg;
                }
            } else {
                authError = null;
            }
            if (this.minecraft.gui.screen() instanceof dev.simplesync.ui.DeviceAuthScreen) {
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
        return Component.literal("Schematics: ").append(state);
    }

    private Component getMasaConfigsText() {
        Component state = Component.translatable(config.syncMasaConfigs ? "options.on" : "options.off");
        return Component.literal("Configs Masa: ").append(state);
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
            for (net.minecraft.util.FormattedCharSequence line : this.font.split(errText, 300)) {
                extractor.centeredText(this.font, line, centerX, currentY, 0xFFE57373);
                currentY += 9;
            }
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (showingTutorial && event.button() == 0) {
            int centerX = this.width / 2;
            int startY = 38;
            int stepGap = 13;

            // Step 1: console.cloud.google.com (index 0)
            int step1Width = this.font.width(Component.translatable("simplesync.tutorial.step1"));
            if (event.y() >= startY - 4 && event.y() <= startY + 13
                    && event.x() >= centerX - step1Width / 2 && event.x() <= centerX + step1Width / 2) {
                confirmAndOpenUrl(this, "https://console.cloud.google.com/");
                return true;
            }

            // Step 2: Google Drive API Library (index 1)
            int step2Width = this.font.width(Component.translatable("simplesync.tutorial.step2"));
            if (event.y() >= (startY + stepGap) - 4 && event.y() <= (startY + stepGap) + 13
                    && event.x() >= centerX - step2Width / 2 && event.x() <= centerX + step2Width / 2) {
                confirmAndOpenUrl(this, "https://console.cloud.google.com/apis/library/drive.googleapis.com");
                return true;
            }

            // Step 4: OAuth Consent Screen — Test Users (index 3)
            int step4Width = this.font.width(Component.translatable("simplesync.tutorial.step4"));
            if (event.y() >= (startY + stepGap * 3) - 4 && event.y() <= (startY + stepGap * 3) + 13
                    && event.x() >= centerX - step4Width / 2 && event.x() <= centerX + step4Width / 2) {
                confirmAndOpenUrl(this, "https://console.cloud.google.com/auth/audience");
                return true;
            }

            // Step 5: Publish App (index 4)
            int step5Width = this.font.width(Component.translatable("simplesync.tutorial.step5"));
            if (event.y() >= (startY + stepGap * 4) - 4 && event.y() <= (startY + stepGap * 4) + 13
                    && event.x() >= centerX - step5Width / 2 && event.x() <= centerX + step5Width / 2) {
                confirmAndOpenUrl(this, "https://console.cloud.google.com/auth/audience");
                return true;
            }

            // Step 8: config/simplesync/ folder (index 7)
            int step8Width = this.font.width(Component.translatable("simplesync.tutorial.step8"));
            if (event.y() >= (startY + stepGap * 7) - 4 && event.y() <= (startY + stepGap * 7) + 13
                    && event.x() >= centerX - step8Width / 2 && event.x() <= centerX + step8Width / 2) {
                dev.simplesync.util.DesktopUtil.openFileRobust(dev.simplesync.config.SyncConfig.getConfigDir().toFile());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    public static void confirmAndOpenUrl(net.minecraft.client.gui.screens.Screen parent, String url) {
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new net.minecraft.client.gui.screens.ConfirmLinkScreen(confirmed -> {
            if (confirmed) {
                dev.simplesync.util.DesktopUtil.openUrl(url);
            }
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(parent);
        }, url, true));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
