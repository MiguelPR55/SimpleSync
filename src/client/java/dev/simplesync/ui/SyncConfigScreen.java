package dev.simplesync.ui;

import dev.simplesync.SimpleSync;
import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

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
        super(Text.translatable("simplesync.config.title"));
        this.parent = parent;
        this.config = SyncConfig.load();
    }

    @Override
    protected void init() {
        this.rebuildWidgets();
    }

    private void rebuildWidgets() {
        // Clear existing widgets
        this.clearChildren();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        if (showingTutorial) {
            int buttonWidth = 150;
            // Add Open Console button
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Google Cloud Console"),
                    button -> SimpleSync.openUrl("https://console.cloud.google.com/"))
                    .dimensions(centerX - buttonWidth - 5, this.height - 65, buttonWidth, 20)
                    .build());

            // Add Open Drive API library button
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Google Drive API Library"),
                    button -> SimpleSync.openUrl("https://console.cloud.google.com/apis/library/drive.googleapis.com"))
                    .dimensions(centerX + 5, this.height - 65, buttonWidth, 20)
                    .build());

            // Add Back button to return to configuration options
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("simplesync.tutorial.back"),
                    button -> {
                        showingTutorial = false;
                        this.rebuildWidgets();
                    })
                    .dimensions(centerX - 100, this.height - 40, 200, 20)
                    .build());
            return;
        }

        // --- NORMAL CONFIGURATION WIDGETS ---
        
        // Auto Sync on Start Button
        this.addDrawableChild(ButtonWidget.builder(
                getAutoStartText(),
                button -> {
                    config.autoSyncOnStart = !config.autoSyncOnStart;
                    config.save();
                    button.setMessage(getAutoStartText());
                })
                .dimensions(centerX - 100, centerY - 55, 200, 20)
                .build());

        // Auto Sync on Exit Button
        this.addDrawableChild(ButtonWidget.builder(
                getAutoExitText(),
                button -> {
                    config.autoSyncOnExit = !config.autoSyncOnExit;
                    config.save();
                    button.setMessage(getAutoExitText());
                })
                .dimensions(centerX - 100, centerY - 30, 200, 20)
                .build());

        // Google Drive Account Sync/Authentication Button
        authenticated = !authenticating && CloudSyncManager.getInstance().getProvider().isAuthenticated();

        Text authBtnText;
        if (authenticating) {
            authBtnText = Text.translatable("simplesync.config.connecting");
        } else if (authenticated) {
            authBtnText = Text.translatable("simplesync.config.disconnect");
        } else {
            authBtnText = Text.translatable("simplesync.config.connect");
        }

        ButtonWidget connectBtn = ButtonWidget.builder(authBtnText, button -> {
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
        .dimensions(centerX - 100, centerY + 20, 200, 20)
        .build();

        connectBtn.active = !authenticating;
        this.addDrawableChild(connectBtn);

        // Help / Setup Tutorial Button
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("simplesync.config.tutorial_btn"),
                button -> {
                    showingTutorial = true;
                    this.rebuildWidgets();
                })
                .dimensions(centerX - 100, centerY + 45, 200, 20)
                .build());

        // Done button to close and return to previous screen
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.done"),
                button -> this.close())
                .dimensions(centerX - 100, centerY + 80, 200, 20)
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
        });
    }

    private void onAuthenticationComplete(boolean success, Exception e) {
        if (this.client == null) return;
        this.client.execute(() -> {
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
            if (this.client.currentScreen instanceof dev.simplesync.ui.DeviceAuthScreen) {
                this.client.setScreen(this);
            }
            this.rebuildWidgets();
        });
    }

    private Text getAutoStartText() {
        String state = config.autoSyncOnStart ? "ON" : "OFF";
        return Text.translatable("simplesync.config.auto_start", state);
    }

    private Text getAutoExitText() {
        String state = config.autoSyncOnExit ? "ON" : "OFF";
        return Text.translatable("simplesync.config.auto_exit", state);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        if (showingTutorial) {
            // --- DRAW TUTORIAL GUIDE SCREEN ---
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("simplesync.tutorial.title"), centerX, 20, 0xFFFFFF);

            int startY = 45;
            int stepGap = 18;

            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("simplesync.tutorial.step1"), centerX, startY, 0xDDDDDD);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("simplesync.tutorial.step2"), centerX, startY + stepGap, 0xDDDDDD);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("simplesync.tutorial.step3"), centerX, startY + stepGap * 2, 0xDDDDDD);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("simplesync.tutorial.step4"), centerX, startY + stepGap * 3, 0xDDDDDD);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("simplesync.tutorial.step5"), centerX, startY + stepGap * 4, 0xDDDDDD);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("simplesync.tutorial.step6"), centerX, startY + stepGap * 5, 0xDDDDDD);
            return;
        }

        // --- DRAW NORMAL CONFIGURATION OPTIONS SCREEN ---
        context.drawCenteredTextWithShadow(this.textRenderer,
                this.title, centerX, 25, 0xFFFFFF);

        int centerY = this.height / 2;

        Text statusTextVal;
        int statusColor;

        if (authenticating) {
            statusTextVal = Text.translatable("simplesync.config.connecting");
            statusColor = 0xFFD54F; // Yellow
        } else if (authenticated) {
            statusTextVal = Text.translatable("simplesync.config.status.connected");
            statusColor = 0x81C784; // Green
        } else {
            statusTextVal = Text.translatable("simplesync.config.status.disconnected");
            statusColor = 0xFFD54F; // Yellow
        }

        Text statusText = Text.translatable("simplesync.config.status", statusTextVal.getString());
        context.drawCenteredTextWithShadow(this.textRenderer, statusText, centerX, centerY - 2, statusColor);

        // Draw authentication errors if any
        if (authError != null) {
            Text errText = Text.translatable("simplesync.config.auth_failed", authError);
            context.drawCenteredTextWithShadow(this.textRenderer, errText, centerX, centerY + 2, 0xE57373);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showingTutorial && button == 0) {
            int centerX = this.width / 2;
            int startY = 45;
            // Use a generous and robust click box around the center of the first step
            if (mouseY >= startY - 4 && mouseY <= startY + 14 && mouseX >= centerX - 150 && mouseX <= centerX + 150) {
                SimpleSync.openUrl("https://console.cloud.google.com/");
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
