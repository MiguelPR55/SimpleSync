package dev.simplesync.ui;

import dev.simplesync.cloud.CloudProvider;
import dev.simplesync.cloud.CloudSyncManager;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.WorldMetadata;
import dev.simplesync.util.SyncLogger;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Screen that lists all world backups stored in Google Drive.
 * Allows the user to restore / download older or deleted worlds, or delete cloud backups.
 */
public class CloudWorldsScreen extends Screen {

    private static final int ITEMS_PER_PAGE = 5;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Screen parent;
    private List<WorldMetadata> cloudWorlds = new ArrayList<>();
    private boolean loading = true;
    private boolean refreshInFlight = false;
    private String errorMessage = null;
    private int currentPage = 0;
    private final Map<String, Boolean> installedCache = new ConcurrentHashMap<>();
    private long lastCacheRefresh = 0;

    public CloudWorldsScreen(Screen parent) {
        super(Component.translatable("simplesync.cloud_worlds.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();

        if (this.loading && !this.refreshInFlight && this.cloudWorlds.isEmpty() && this.errorMessage == null) {
            refreshList();
        }

        int bottomY = this.height - 30;

        // Back / Done Button
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> {
            returnToParent();
        }).bounds(this.width / 2 - 100, bottomY, 200, 20).build());

        if (this.errorMessage != null || (this.cloudWorlds.isEmpty() && !this.loading)) {
            // Refresh / Retry button centered when empty or on error
            this.addRenderableWidget(Button.builder(
                    Component.translatable("simplesync.cloud_worlds.refresh"),
                    btn -> refreshList()
            ).bounds(this.width / 2 - 80, this.height / 2 + 10, 160, 20).build());
        }

        if (!this.loading && this.errorMessage == null && !this.cloudWorlds.isEmpty()) {
            // Refresh button in top corner
            this.addRenderableWidget(Button.builder(
                    Component.translatable("simplesync.cloud_worlds.refresh"),
                    btn -> refreshList()
            ).bounds(this.width - 100, 10, 90, 18).build());

            int totalPages = (this.cloudWorlds.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
            if (totalPages > 1) {
                // Prev Page Button
                Button prevBtn = Button.builder(Component.literal("<"), btn -> {
                    if (this.currentPage > 0) {
                        this.currentPage--;
                        this.rebuildWidgets();
                    }
                }).bounds(this.width / 2 - 130, bottomY, 25, 20).build();
                prevBtn.active = this.currentPage > 0;
                this.addRenderableWidget(prevBtn);

                // Next Page Button
                Button nextBtn = Button.builder(Component.literal(">"), btn -> {
                    if (this.currentPage < totalPages - 1) {
                        this.currentPage++;
                        this.rebuildWidgets();
                    }
                }).bounds(this.width / 2 + 105, bottomY, 25, 20).build();
                nextBtn.active = this.currentPage < totalPages - 1;
                this.addRenderableWidget(nextBtn);
            }

            // Render buttons for each item on current page
            int startIdx = this.currentPage * ITEMS_PER_PAGE;
            int endIdx = Math.min(this.cloudWorlds.size(), startIdx + ITEMS_PER_PAGE);
            Path savesDir = CloudSyncManager.getInstance().getSavesDirectory();

            for (int i = startIdx; i < endIdx; i++) {
                WorldMetadata meta = this.cloudWorlds.get(i);
                int rowY = 45 + (i - startIdx) * 30;

                Path worldFolder = savesDir.resolve(meta.worldName()).normalize();
                boolean isInstalled = Files.isDirectory(worldFolder);

                // Restore / Download button
                Button restoreBtn = Button.builder(
                        Component.translatable(isInstalled ? "simplesync.cloud_worlds.reinstall" : "simplesync.cloud_worlds.restore"),
                        btn -> {
                            btn.active = false;
                            CloudSyncManager.getInstance().restoreWorldFromCloudAsync(meta.worldName(), () -> {
                                if (this.minecraft != null) {
                                    this.minecraft.execute(this::refreshList);
                                }
                            });
                        }
                ).bounds(this.width - 220, rowY, 100, 20).build();
                this.addRenderableWidget(restoreBtn);

                // Delete from Drive button
                Button deleteBtn = Button.builder(
                        Component.translatable("simplesync.cloud_worlds.delete"),
                        btn -> {
                            this.minecraft.gui.setScreen(new ConfirmScreen(confirm -> {
                                if (confirm) {
                                    CloudSyncManager.getInstance().deleteWorldFromCloudAsync(meta.worldName()).thenAccept(success -> {
                                        if (success && this.minecraft != null) {
                                            this.minecraft.execute(() -> {
                                                this.cloudWorlds.remove(meta);
                                                if (this.currentPage * ITEMS_PER_PAGE >= this.cloudWorlds.size() && this.currentPage > 0) {
                                                    this.currentPage--;
                                                }
                                                this.rebuildWidgets();
                                            });
                                        }
                                    });
                                }
                                this.minecraft.gui.setScreen(this);
                            }, Component.translatable("selectWorld.deleteQuestion"), Component.translatable("selectWorld.deleteWarning", meta.worldName()), Component.translatable("selectWorld.deleteButton"), CommonComponents.GUI_CANCEL));
                        }
                ).bounds(this.width - 115, rowY, 95, 20).build();
                this.addRenderableWidget(deleteBtn);
            }
        }
    }

    private void refreshList() {
        if (this.refreshInFlight) {
            return;
        }
        this.refreshInFlight = true;
        this.loading = true;
        this.errorMessage = null;
        this.rebuildWidgets();

        CompletableFuture.runAsync(() -> {
            try {
                CloudProvider cloud = CloudSyncManager.getInstance().getProvider();
                if (cloud == null) {
                    throw new IllegalStateException("Cloud provider not initialized");
                }
                CloudSyncManager.getInstance().ensureAuthenticatedOrThrow(cloud, this::refreshList);

                List<WorldMetadata> list = cloud.listWorlds();
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        this.cloudWorlds = list != null ? list : new ArrayList<>();
                        this.loading = false;
                        this.refreshInFlight = false;
                        this.rebuildWidgets();
                    });
                }
            } catch (Exception e) {
                handleError(e);
            }
        }, CloudSyncManager.getInstance().getExecutor());
    }

    private void handleError(Exception e) {
        SyncLogger.error("[SimpleSync] Failed to fetch cloud worlds list", e);
        if (this.minecraft != null) {
            this.minecraft.execute(() -> {
                this.errorMessage = e.getMessage() != null ? e.getMessage() : "Error connecting to Google Drive";
                this.loading = false;
                this.refreshInFlight = false;
                this.rebuildWidgets();
            });
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        // Title
        extractor.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);

        if (this.loading) {
            extractor.centeredText(this.font, Component.translatable("simplesync.cloud_worlds.loading"), this.width / 2, this.height / 2 - 10, 0xFFFFFF00);
            return;
        }

        if (this.errorMessage != null) {
            extractor.centeredText(this.font, Component.translatable("simplesync.cloud_worlds.error", this.errorMessage), this.width / 2, this.height / 2 - 10, 0xFFFF5555);
            return;
        }

        if (this.cloudWorlds.isEmpty()) {
            extractor.centeredText(this.font, Component.translatable("simplesync.cloud_worlds.empty"), this.width / 2, this.height / 2 - 10, 0xFFDDDDDD);
            return;
        }

        int startIdx = this.currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(this.cloudWorlds.size(), startIdx + ITEMS_PER_PAGE);
        Path savesDir = CloudSyncManager.getInstance().getSavesDirectory();
        SyncConfig config = SyncConfig.load();

        long now = System.currentTimeMillis();
        if (now - lastCacheRefresh > 2000) {
            installedCache.clear();
            lastCacheRefresh = now;
        }

        for (int i = startIdx; i < endIdx; i++) {
            WorldMetadata meta = this.cloudWorlds.get(i);
            int rowY = 45 + (i - startIdx) * 30;

            Path worldFolder = savesDir.resolve(meta.worldName()).normalize();
            boolean isInstalled = installedCache.computeIfAbsent(meta.worldName(), k -> Files.isDirectory(worldFolder));
            boolean isIgnored = config.ignoredCloudWorlds != null && config.ignoredCloudWorlds.contains(meta.worldName());

            // Draw world name
            extractor.text(this.font, meta.worldName(), 20, rowY + 2, 0xFFFFFFFF);

            // Draw status / date
            String dateStr = meta.lastModified() > 0 ? DATE_FORMATTER.format(Instant.ofEpochMilli(meta.lastModified())) : "";
            Component statusComp;
            int statusColor;
            if (isInstalled) {
                statusComp = Component.translatable("simplesync.cloud_worlds.status.installed");
                statusColor = 0xFF55FF55; // Green
            } else if (isIgnored) {
                statusComp = Component.translatable("simplesync.cloud_worlds.status.archived");
                statusColor = 0xFF55FFFF; // Cyan/Yellow
            } else {
                statusComp = Component.translatable("simplesync.cloud_worlds.status.cloud_only");
                statusColor = 0xFFAAAAAA; // Gray
            }

            String infoText = statusComp.getString() + (dateStr.isEmpty() ? "" : " - " + dateStr);
            extractor.text(this.font, infoText, 20, rowY + 13, statusColor);
        }

        // Draw page indicator
        int totalPages = (this.cloudWorlds.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        if (totalPages > 1) {
            String pageStr = (this.currentPage + 1) + " / " + totalPages;
            extractor.centeredText(this.font, Component.literal(pageStr), this.width / 2, this.height - 25, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        returnToParent();
    }

    private void returnToParent() {
        if (this.parent instanceof ReloadableScreen reloadable) {
            reloadable.reloadAndReturn();
        } else if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
