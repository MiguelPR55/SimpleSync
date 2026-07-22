package dev.simplesync.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.util.RetryUtil;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Manages Google Drive folder hierarchy: SimpleSync root, Worlds, Schematics, Configs.
 * Caches folder IDs and handles creation/verification.
 */
public class DriveFolderManager {

    private static final String SIMPLESYNC_FOLDER_NAME = "SimpleSync";
    private static final Pattern DRIVE_FILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");

    private final DriveApiClient api;
    private volatile String simpleSyncFolderId;
    private final Map<String, Optional<String>> fileIdCache = new ConcurrentHashMap<>();

    public DriveFolderManager(DriveApiClient api) {
        this.api = api;
    }

    // ─── Root Folder ──────────────────────────────────────────────────────

    public synchronized String getSimpleSyncFolderId() throws IOException {
        if (simpleSyncFolderId != null) return simpleSyncFolderId;

        SyncConfig config = SyncConfig.load();
        String savedId = config.simpleSyncFolderId;

        if (savedId != null && !savedId.isEmpty()) {
            if (isSafeDriveFileId(savedId)) {
                try {
                    HttpRequest.Builder req = api.authedRequest(
                            "https://www.googleapis.com/drive/v3/files/" + savedId + "?fields=id,trashed",
                            Duration.ofSeconds(15)).GET();
                    HttpResponse<String> resp = api.send(req, 3);
                    if (resp.statusCode() == 200) {
                        JsonObject folder = JsonParser.parseString(resp.body()).getAsJsonObject();
                        if (!folder.has("trashed") || !folder.get("trashed").getAsBoolean()) {
                            simpleSyncFolderId = savedId;
                            return simpleSyncFolderId;
                        }
                    } else if (resp.statusCode() == 404) {
                        config.simpleSyncFolderId = null;
                        config.save();
                    }
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("404")) {
                        config.simpleSyncFolderId = null;
                        config.save();
                    } else {
                        throw e;
                    }
                }
            } else {
                config.simpleSyncFolderId = null;
                config.save();
            }
        }

        // Search for existing folder
        String query = DriveApiClient.buildQuery(
                "name='%s' and mimeType='application/vnd.google-apps.folder' and trashed=false",
                SIMPLESYNC_FOLDER_NAME);
        String searchUrl = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query)
                + "&fields=files(id)&orderBy=createdTime";

        HttpRequest.Builder searchReq = api.authedRequest(searchUrl, Duration.ofSeconds(30)).GET();
        HttpResponse<String> searchResp = api.send(searchReq, 3);
        if (searchResp.statusCode() == 200) {
            JsonObject body = JsonParser.parseString(searchResp.body()).getAsJsonObject();
            JsonArray files = body.getAsJsonArray("files");
            if (files != null && files.size() > 0) {
                simpleSyncFolderId = files.get(0).getAsJsonObject().get("id").getAsString();
                config.simpleSyncFolderId = simpleSyncFolderId;
                config.save();
                return simpleSyncFolderId;
            }
        }

        // Create folder
        JsonObject meta = new JsonObject();
        meta.addProperty("name", SIMPLESYNC_FOLDER_NAME);
        meta.addProperty("mimeType", "application/vnd.google-apps.folder");

        HttpRequest.Builder createReq = api.authedRequest(
                "https://www.googleapis.com/drive/v3/files?fields=id", Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(meta.toString()))
                .header("Content-Type", "application/json; charset=UTF-8");

        HttpResponse<String> createResp = api.send(createReq, 3);
        if (createResp.statusCode() == 200 || createResp.statusCode() == 201) {
            JsonObject folder = JsonParser.parseString(createResp.body()).getAsJsonObject();
            simpleSyncFolderId = folder.get("id").getAsString();
            config.simpleSyncFolderId = simpleSyncFolderId;
            config.save();
            return simpleSyncFolderId;
        }
        throw new IOException("Folder creation failed: HTTP " + createResp.statusCode());
    }

    // ─── Subfolder Management ─────────────────────────────────────────────

    public synchronized String getOrCreateSubfolder(String parentFolderId, String folderName) throws IOException {
        String query = DriveApiClient.buildQuery(
                "name='%s' and '%s' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false",
                folderName, parentFolderId);
        String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query) + "&fields=files(id)&pageSize=1";

        HttpRequest.Builder searchReq = api.authedRequest(url, Duration.ofSeconds(30)).GET();
        HttpResponse<String> searchResp = api.send(searchReq, 3);
        if (searchResp.statusCode() == 200) {
            JsonObject body = JsonParser.parseString(searchResp.body()).getAsJsonObject();
            JsonArray files = body.getAsJsonArray("files");
            if (files != null && files.size() > 0) {
                return files.get(0).getAsJsonObject().get("id").getAsString();
            }
        }

        JsonObject meta = new JsonObject();
        meta.addProperty("name", folderName);
        meta.addProperty("mimeType", "application/vnd.google-apps.folder");
        JsonArray parents = new JsonArray();
        parents.add(parentFolderId);
        meta.add("parents", parents);

        HttpRequest.Builder createReq = api.authedRequest(
                "https://www.googleapis.com/drive/v3/files?fields=id", Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(meta.toString()))
                .header("Content-Type", "application/json; charset=UTF-8");

        HttpResponse<String> createResp = api.send(createReq, 3);
        if (createResp.statusCode() == 200 || createResp.statusCode() == 201) {
            return JsonParser.parseString(createResp.body()).getAsJsonObject().get("id").getAsString();
        }
        throw new IOException("Failed to create folder '" + folderName + "': HTTP " + createResp.statusCode());
    }

    // ─── Generic Subfolder ID Getter (eliminates 3 duplicated methods) ────

    public String getSubfolderId(String folderName,
                                 Function<SyncConfig, String> getter,
                                 BiConsumer<SyncConfig, String> setter) throws IOException {
        getSimpleSyncFolderId(); // ensure root exists
        SyncConfig config = SyncConfig.load();
        String cached = getter.apply(config);
        if (cached != null && isSafeDriveFileId(cached)) return cached;
        String id = getOrCreateSubfolder(simpleSyncFolderId, folderName);
        setter.accept(config, id);
        config.save();
        return id;
    }

    public String getWorldsFolderId() throws IOException {
        return getSubfolderId("Worlds", c -> c.worldsFolderId, (c, id) -> c.worldsFolderId = id);
    }

    public String getSchematicsFolderId() throws IOException {
        return getSubfolderId("Schematics", c -> c.schematicsFolderId, (c, id) -> c.schematicsFolderId = id);
    }

    public String getConfigsFolderId() throws IOException {
        return getSubfolderId("Configs", c -> c.configsFolderId, (c, id) -> c.configsFolderId = id);
    }

    // ─── File ID Cache ────────────────────────────────────────────────────

    public String findFileId(String fileName) throws IOException {
        if (fileIdCache.containsKey(fileName)) {
            return fileIdCache.get(fileName).orElse(null);
        }

        String rootId = getSimpleSyncFolderId();
        String worldsId = getWorldsFolderId();

        String query = DriveApiClient.buildQuery(
                "name='%s' and ('%s' in parents or '%s' in parents) and trashed=false",
                fileName, worldsId, rootId);
        String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query)
                + "&fields=files(id)&orderBy=modifiedTime%20desc";

        HttpRequest.Builder req = api.authedRequest(url, Duration.ofSeconds(30)).GET();
        HttpResponse<String> resp = api.send(req, 3);
        if (resp.statusCode() == 200) {
            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray files = body.getAsJsonArray("files");
            if (files != null && files.size() > 0) {
                String id = files.get(0).getAsJsonObject().get("id").getAsString();
                fileIdCache.put(fileName, Optional.of(id));
                return id;
            }
            fileIdCache.put(fileName, Optional.empty());
            return null;
        }
        throw new IOException("Failed to search for file '" + fileName + "': HTTP " + resp.statusCode());
    }

    public void cacheFileId(String fileName, String fileId) {
        fileIdCache.put(fileName, Optional.ofNullable(fileId));
    }

    public void removeCachedFileId(String fileName) {
        fileIdCache.remove(fileName);
    }

    public void clearCache() {
        fileIdCache.clear();
    }

    // ─── Utilities ────────────────────────────────────────────────────────

    public static boolean isSafeDriveFileId(String fileId) {
        return fileId != null && DRIVE_FILE_ID_PATTERN.matcher(fileId).matches();
    }

    public void invalidateRoot() {
        simpleSyncFolderId = null;
    }
}
