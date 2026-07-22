package dev.simplesync.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.FolderSyncTask;
import dev.simplesync.util.RetryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Handles incremental file-level synchronization for schematics and masa configs.
 */
public class DriveSyncEngine {

    private final DriveApiClient api;
    private final DriveFolderManager folders;
    private final ExecutorService filePool;

    public DriveSyncEngine(DriveApiClient api, DriveFolderManager folders, ExecutorService filePool) {
        this.api = api;
        this.folders = folders;
        this.filePool = filePool;
    }

    // ─── Public Entry Points ──────────────────────────────────────────────

    public void syncSchematics(Path gameRootDir) throws IOException {
        Path schematicsDir = gameRootDir.resolve("schematics");
        Files.createDirectories(schematicsDir);
        String remoteFolderId = folders.getSchematicsFolderId();
        List<FolderSyncTask.LocalFileInfo> localFiles = FolderSyncTask.scanLocalDirectory(schematicsDir);
        syncDirectoryIncremental(remoteFolderId, schematicsDir, localFiles);
    }

    public void syncMasaConfigs(Path gameRootDir) throws IOException {
        String remoteFolderId = folders.getConfigsFolderId();
        List<FolderSyncTask.LocalFileInfo> localFiles = FolderSyncTask.scanMasaConfigFiles(gameRootDir);
        syncDirectoryIncremental(remoteFolderId, gameRootDir, localFiles);
    }

    // ─── Core Incremental Sync ────────────────────────────────────────────

    private record DriveItem(String id, String name, String parentId, String mimeType, long modifiedTime, long size) {}

    private void syncDirectoryIncremental(String rootRemoteFolderId, Path localBaseDir,
                                          List<FolderSyncTask.LocalFileInfo> localFiles) throws IOException {
        List<DriveItem> allRemoteItems = listAllDriveItemsUnder(rootRemoteFolderId);

        SyncConfig config = SyncConfig.load();
        List<FolderSyncTask.RemoteFileInfo> remoteFiles = reconstructRemoteFileInfos(allRemoteItems, rootRemoteFolderId);
        FolderSyncTask.SyncPlan plan = FolderSyncTask.createSyncPlan(localFiles, remoteFiles, config.fileTracking);

        Map<String, String> folderPathToIdMap = new ConcurrentHashMap<>(reconstructRemoteFolderMap(allRemoteItems, rootRemoteFolderId));
        folderPathToIdMap.put("", rootRemoteFolderId);

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (FolderSyncTask.LocalFileInfo local : plan.toUpload()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        uploadSingleFile(rootRemoteFolderId, localBaseDir, local, folderPathToIdMap, remoteFiles);
                        long now = System.currentTimeMillis();
                        synchronized (config) {
                            config.setFileTracking(local.relativePath(), new SyncConfig.FileTrackingInfo(now, local.size(), local.lastModified()));
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, filePool));
            }

            for (FolderSyncTask.RemoteFileInfo remote : plan.toDownload()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        downloadSingleFile(localBaseDir, remote);
                        long now = System.currentTimeMillis();
                        long localMtime = remote.lastModified() > 0 ? remote.lastModified() : now;
                        synchronized (config) {
                            config.setFileTracking(remote.relativePath(), new SyncConfig.FileTrackingInfo(localMtime, remote.size(), localMtime));
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, filePool));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            config.save();
        } catch (CompletionException ce) {
            config.save();
            if (ce.getCause() instanceof java.io.UncheckedIOException uioe) {
                throw uioe.getCause();
            }
            throw new IOException("Parallel incremental sync failed: " + ce.getMessage(), ce);
        }
    }

    // ─── Upload / Download Single File ────────────────────────────────────

    private void uploadSingleFile(String rootFolderId, Path localBaseDir,
                                  FolderSyncTask.LocalFileInfo local,
                                  Map<String, String> folderMap,
                                  List<FolderSyncTask.RemoteFileInfo> remoteFiles) throws IOException {
        String relPath = local.relativePath();
        int lastSlash = relPath.lastIndexOf('/');
        String parentRelPath = lastSlash > 0 ? relPath.substring(0, lastSlash) : "";
        String fileName = lastSlash >= 0 ? relPath.substring(lastSlash + 1) : relPath;

        String parentFolderId = resolveOrCreateRemoteFolderPath(parentRelPath, rootFolderId, folderMap);

        String existingFileId = remoteFiles.stream()
                .filter(r -> r.relativePath().equals(relPath))
                .map(FolderSyncTask.RemoteFileInfo::fileId)
                .findFirst().orElse(null);

        if (existingFileId != null) {
            String url = "https://www.googleapis.com/upload/drive/v3/files/" + existingFileId + "?uploadType=media";
            HttpRequest.Builder req = api.authedRequest(url, Duration.ofSeconds(60))
                    .method("PATCH", HttpRequest.BodyPublishers.ofFile(local.fullPath()))
                    .header("Content-Type", "application/octet-stream");
            HttpResponse<String> resp = api.send(req, 3);
            if (resp.statusCode() != 200) {
                throw new IOException("Failed to update file " + relPath + ": HTTP " + resp.statusCode());
            }
        } else {
            if (local.size() > 5L * 1024 * 1024) {
                // Resumable streaming upload for larger files
                String initUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,modifiedTime,size";
                JsonObject meta = new JsonObject();
                meta.addProperty("name", fileName);
                JsonArray parents = new JsonArray();
                parents.add(parentFolderId);
                meta.add("parents", parents);

                HttpRequest.Builder initReq = api.authedRequest(initUrl, Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(meta.toString()))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("X-Upload-Content-Type", "application/octet-stream")
                        .header("X-Upload-Content-Length", String.valueOf(local.size()));

                HttpResponse<String> initResp = api.send(initReq, 3);
                if (initResp.statusCode() != 200 && initResp.statusCode() != 201) {
                    throw new IOException("Failed to init resumable upload for " + relPath + ": HTTP " + initResp.statusCode());
                }

                Optional<String> location = initResp.headers().firstValue("Location");
                if (location.isEmpty()) throw new IOException("No Location header for resumable upload of " + relPath);

                HttpRequest.Builder putReq = HttpRequest.newBuilder(java.net.URI.create(location.get()))
                        .PUT(HttpRequest.BodyPublishers.ofFile(local.fullPath()))
                        .timeout(Duration.ofMinutes(15))
                        .header("Content-Type", "application/octet-stream");

                HttpResponse<String> putResp = api.send(putReq, 3);
                if (putResp.statusCode() != 200 && putResp.statusCode() != 201) {
                    throw new IOException("Failed to upload new file " + relPath + ": HTTP " + putResp.statusCode());
                }
            } else {
                byte[] fileBytes = Files.readAllBytes(local.fullPath());
                String boundary = "SimpleSyncBoundary" + System.currentTimeMillis();

                JsonObject meta = new JsonObject();
                meta.addProperty("name", fileName);
                JsonArray parents = new JsonArray();
                parents.add(parentFolderId);
                meta.add("parents", parents);

                byte[] bodyBytes = DriveApiClient.buildMultipartBody(boundary, meta.toString(), fileBytes);

                HttpRequest.Builder req = api.authedRequest(
                        "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                        .header("Content-Type", "multipart/related; boundary=" + boundary);

                HttpResponse<String> resp = api.send(req, 3);
                if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                    throw new IOException("Failed to upload new file " + relPath + ": HTTP " + resp.statusCode());
                }
            }
        }
    }

    private void downloadSingleFile(Path localBaseDir, FolderSyncTask.RemoteFileInfo remote) throws IOException {
        Path targetFile = localBaseDir.resolve(remote.relativePath()).normalize();
        if (!targetFile.startsWith(localBaseDir.normalize())) {
            throw new SecurityException("Path traversal detected: " + remote.relativePath());
        }
        Files.createDirectories(targetFile.getParent());

        String url = "https://www.googleapis.com/drive/v3/files/" + remote.fileId() + "?alt=media";
        HttpRequest.Builder req = api.authedRequest(url, Duration.ofSeconds(60)).GET();
        HttpResponse<InputStream> resp = api.send(req, HttpResponse.BodyHandlers.ofInputStream(), 3);

        if (resp.statusCode() != 200) {
            closeBodyQuietly(resp.body());
            throw new IOException("Failed to download " + remote.relativePath() + ": HTTP " + resp.statusCode());
        }

        try (InputStream is = resp.body();
             OutputStream os = Files.newOutputStream(targetFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            is.transferTo(os);
        } catch (IOException e) {
            try { Files.deleteIfExists(targetFile); } catch (IOException ignored) {}
            throw e;
        }

        if (remote.lastModified() > 0) {
            try { Files.setLastModifiedTime(targetFile, FileTime.fromMillis(remote.lastModified())); }
            catch (IOException ignored) {}
        }
    }

    // ─── Remote Listing ───────────────────────────────────────────────────

    private List<DriveItem> listAllDriveItemsUnder(String rootFolderId) throws IOException {
        List<DriveItem> result = new ArrayList<>();
        List<String> currentLevel = List.of(rootFolderId);
        Set<String> visited = new HashSet<>();
        visited.add(rootFolderId);

        while (!currentLevel.isEmpty()) {
            List<String> nextLevel = new ArrayList<>();

            for (int i = 0; i < currentLevel.size(); i += 30) {
                List<String> chunk = currentLevel.subList(i, Math.min(i + 30, currentLevel.size()));
                StringBuilder qb = new StringBuilder("(");
                for (int j = 0; j < chunk.size(); j++) {
                    if (j > 0) qb.append(" or ");
                    qb.append("'").append(DriveApiClient.escapeQueryString(chunk.get(j))).append("' in parents");
                }
                qb.append(") and trashed=false");

                String pageToken = null;
                do {
                    String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(qb.toString())
                            + "&fields=nextPageToken,files(id,name,parents,mimeType,modifiedTime,size)&pageSize=1000"
                            + (pageToken != null ? "&pageToken=" + RetryUtil.urlEncode(pageToken) : "");

                    HttpRequest.Builder req = api.authedRequest(url, Duration.ofSeconds(30)).GET();
                    HttpResponse<String> resp = api.send(req, 3);
                    if (resp.statusCode() != 200) {
                        throw new IOException("Failed to list items: HTTP " + resp.statusCode());
                    }

                    JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
                    JsonArray files = body.getAsJsonArray("files");
                    if (files != null) {
                        for (int k = 0; k < files.size(); k++) {
                            JsonObject f = files.get(k).getAsJsonObject();
                            String id = f.get("id").getAsString();
                            String name = f.get("name").getAsString();
                            String mimeType = f.has("mimeType") ? f.get("mimeType").getAsString() : "";
                            long mtime = f.has("modifiedTime") ? java.time.Instant.parse(f.get("modifiedTime").getAsString()).toEpochMilli() : System.currentTimeMillis();
                            long size = f.has("size") ? f.get("size").getAsLong() : 0L;
                            String parentId = rootFolderId;
                            if (f.has("parents") && f.getAsJsonArray("parents").size() > 0) {
                                parentId = f.getAsJsonArray("parents").get(0).getAsString();
                            }

                            result.add(new DriveItem(id, name, parentId, mimeType, mtime, size));
                            if ("application/vnd.google-apps.folder".equals(mimeType) && visited.add(id)) {
                                nextLevel.add(id);
                            }
                        }
                    }
                    pageToken = body.has("nextPageToken") ? body.get("nextPageToken").getAsString() : null;
                } while (pageToken != null);
            }
            currentLevel = nextLevel;
        }
        return result;
    }

    // ─── Path Reconstruction ──────────────────────────────────────────────

    private List<FolderSyncTask.RemoteFileInfo> reconstructRemoteFileInfos(List<DriveItem> items, String rootFolderId) {
        Map<String, DriveItem> itemMap = new HashMap<>();
        for (DriveItem item : items) itemMap.put(item.id(), item);

        List<FolderSyncTask.RemoteFileInfo> result = new ArrayList<>();
        for (DriveItem item : items) {
            if ("application/vnd.google-apps.folder".equals(item.mimeType())) continue;
            String relPath = buildRelativePath(item, itemMap, rootFolderId);
            if (relPath != null) {
                result.add(new FolderSyncTask.RemoteFileInfo(relPath, item.id(), item.modifiedTime(), item.size()));
            }
        }
        return result;
    }

    private Map<String, String> reconstructRemoteFolderMap(List<DriveItem> items, String rootFolderId) {
        Map<String, DriveItem> itemMap = new HashMap<>();
        for (DriveItem item : items) itemMap.put(item.id(), item);

        Map<String, String> result = new HashMap<>();
        for (DriveItem item : items) {
            if ("application/vnd.google-apps.folder".equals(item.mimeType())) {
                String relPath = buildRelativePath(item, itemMap, rootFolderId);
                if (relPath != null) result.put(relPath, item.id());
            }
        }
        return result;
    }

    private String buildRelativePath(DriveItem item, Map<String, DriveItem> itemMap, String rootFolderId) {
        List<String> parts = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        DriveItem curr = item;
        while (curr != null && !curr.id().equals(rootFolderId)) {
            if (!visited.add(curr.id())) return null;
            parts.add(0, curr.name());
            String parentId = curr.parentId();
            if (parentId == null || parentId.equals(rootFolderId)) break;
            curr = itemMap.get(parentId);
        }
        return String.join("/", parts);
    }

    private synchronized String resolveOrCreateRemoteFolderPath(String relFolderPath, String rootFolderId,
                                                                Map<String, String> folderMap) throws IOException {
        if (relFolderPath == null || relFolderPath.isEmpty()) return rootFolderId;
        if (folderMap.containsKey(relFolderPath)) return folderMap.get(relFolderPath);

        String[] parts = relFolderPath.split("/");
        String currentRel = "";
        String currentParentId = rootFolderId;

        for (String part : parts) {
            currentRel = currentRel.isEmpty() ? part : currentRel + "/" + part;
            if (folderMap.containsKey(currentRel)) {
                currentParentId = folderMap.get(currentRel);
            } else {
                currentParentId = folders.getOrCreateSubfolder(currentParentId, part);
                folderMap.put(currentRel, currentParentId);
            }
        }
        return currentParentId;
    }

    private static void closeBodyQuietly(Object body) {
        if (body instanceof InputStream is) {
            try { is.close(); } catch (Exception ignored) {}
        }
    }
}
