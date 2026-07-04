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
                .bounds(centerX - 100, centerY - 65, 200, 20)
                .build());

        // Auto Sync on Exit Button
        this.addRenderableWidget(Button.builder(
                getAutoExitText(),
                button -> {
                    config.autoSyncOnExit = !config.autoSyncOnExit;
                    config.save();
                    button.setMessage(getAutoExitText());
                })
                .bounds(centerX - 100, centerY - 40, 200, 20)
                .build());

        // Google Drive Account Sync/Authentication Button
        authenticated = !authenticating && CloudSyncManager.getInstance().getProvider().isAuthenticated();
        
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
        .bounds(centerX - 100, centerY + 25, 200, 20)
        .build();

        connectBtn.active = !authenticating;
        this.addRenderableWidget(connectBtn);

        // Help / Setup Tutorial Button
        this.addRenderableWidget(Button.builder(
                Component.translatable("simplesync.config.tutorial_btn"),
                button -> {
                    showingTutorial = true;
                    this.rebuildWidgets();
                })
                .bounds(centerX - 100, centerY + 50, 200, 20)
                .build());

        // Done button to close and return to previous screen
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> this.onClose())
                .bounds(centerX - 100, centerY + 85, 200, 20)
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
            authenticating = false;
            authenticated = success;
            if (!success && e != null) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("cancelled by user") || msg.contains("interrupted")) {
                    authError = null;
                } else {
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
        String state = config.autoSyncOnStart ? "ON" : "OFF";
        return Component.translatable("simplesync.config.auto_start", state);
    }

    private Component getAutoExitText() {
        String state = config.autoSyncOnExit ? "ON" : "OFF";
        return Component.translatable("simplesync.config.auto_exit", state);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        if (showingTutorial) {
            // --- DRAW TUTORIAL GUIDE SCREEN ---
            extractor.centeredText(this.font,
                    Component.translatable("simplesync.tutorial.title"), centerX, 20, 0xFFFFFFFF);

            int startY = 45;
            int stepGap = 18;

            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step1"), centerX, startY, 0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step2"), centerX, startY + stepGap, 0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step3"), centerX, startY + stepGap * 2, 0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step4"), centerX, startY + stepGap * 3, 0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step5"), centerX, startY + stepGap * 4, 0xFFDDDDDD);
            extractor.centeredText(this.font, Component.translatable("simplesync.tutorial.step6"), centerX, startY + stepGap * 5, 0xFFDDDDDD);
            return;
        }

        // --- DRAW NORMAL CONFIGURATION OPTIONS SCREEN ---
        extractor.centeredText(this.font,
                this.title, centerX, 25, 0xFFFFFFFF);

        int centerY = this.height / 2;

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
        extractor.centeredText(this.font, statusText, centerX, centerY - 15, statusColor);

        // Draw authentication errors if any (split into multiple centered lines to avoid overlap)
        if (authError != null) {
            Component errText = Component.translatable("simplesync.config.auth_failed", authError);
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(errText, 300);
            int currentY = centerY - 3;
            for (net.minecraft.util.FormattedCharSequence line : lines) {
                extractor.centeredText(this.font, line, centerX, currentY, 0xFFE57373);
                currentY += 9;
            }
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (showingTutorial && event.button() == 0) {
            int centerX = this.width / 2;
            int startY = 45;
            int stepGap = 18;

            // Step 1: console.cloud.google.com
            int step1Width = this.font.width(Component.translatable("simplesync.tutorial.step1"));
            if (event.y() >= startY - 4 && event.y() <= startY + 14 && event.x() >= centerX - step1Width / 2 && event.x() <= centerX + step1Width / 2) {
                net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, "https://console.cloud.google.com/");
                return true;
            }

            // Step 2: Google Drive API Library
            int step2Width = this.font.width(Component.translatable("simplesync.tutorial.step2"));
            if (event.y() >= (startY + stepGap) - 4 && event.y() <= (startY + stepGap) + 14 && event.x() >= centerX - step2Width / 2 && event.x() <= centerX + step2Width / 2) {
                net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, "https://console.cloud.google.com/apis/library/drive.googleapis.com");
                return true;
            }

            // Step 6: Local config/simplesync/ Folder
            int step6Width = this.font.width(Component.translatable("simplesync.tutorial.step6"));
            if (event.y() >= (startY + stepGap * 5) - 4 && event.y() <= (startY + stepGap * 5) + 14 && event.x() >= centerX - step6Width / 2 && event.x() <= centerX + step6Width / 2) {
                net.minecraft.util.Util.getPlatform().openUri(dev.simplesync.config.SyncConfig.getConfigDir().toUri());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
