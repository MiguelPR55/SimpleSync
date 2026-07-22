package dev.simplesync.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.WorldMetadata;
import dev.simplesync.sync.WorldSyncTask;
import dev.simplesync.util.RetryUtil;

import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google Drive implementation of CloudProvider.
 * Delegates to DriveApiClient, DriveTokenManager, DriveFolderManager, DriveSyncEngine.
 */
public class GoogleDriveProvider implements CloudProvider {

    private static final String ZIP_MIME = "application/zip";
    private static final String TAR_ZST_MIME = "application/zstd";

    private final Path credentialsDir;
    private final HttpClient httpClient;
    private final DriveTokenManager tokenManager;
    private final DriveApiClient api;
    private final DriveFolderManager folders;
    private final DriveSyncEngine syncEngine;
    private final AtomicBoolean isAuthenticating = new AtomicBoolean(false);

    private record ArchiveFileIds(String tarZstId, String zipId) {}

    public GoogleDriveProvider() {
        this.credentialsDir = SyncConfig.getConfigDir().resolve("credentials");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .executor(Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "SimpleSync-HTTP");
                    t.setDaemon(true);
                    return t;
                }))
                .build();

        this.tokenManager = new DriveTokenManager(httpClient);
        this.api = new DriveApiClient(httpClient, tokenManager);
        this.folders = new DriveFolderManager(api);

        var filePool = Executors.newFixedThreadPool(
                Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())),
                r -> { Thread t = new Thread(r, "SimpleSync-FileWorker"); t.setDaemon(true); return t; });
        this.syncEngine = new DriveSyncEngine(api, folders, filePool);
    }

    // ─── CloudProvider Interface ──────────────────────────────────────────

    @Override public String getName() { return "Google Drive"; }

    @Override
    public boolean isAuthenticated() {
        try {
            TokenStore.TokenData tokens = TokenStore.load();
            return tokens != null && tokens.refreshToken != null && !tokens.refreshToken.isEmpty();
        } catch (Exception e) {
            SimpleSync.LOGGER.error("[SimpleSync] Error checking auth status", e);
            return false;
        }
    }

    @Override public boolean isAuthenticating() { return isAuthenticating.get(); }

    @Override
    public void authenticate() throws IOException {
        if (isAuthenticated()) return;
        if (!isAuthenticating.compareAndSet(false, true)) {
            throw new IOException("Authentication already in progress.");
        }
        try {
            DriveTokenManager.ClientSecrets secrets = tokenManager.loadClientSecrets();
            if (secrets == null) {
                throw new IOException("client_secret.json is missing!");
            }
            var details = secrets.getDetails();
            new DeviceCodeAuthenticator().authenticate(
                    details.client_id, details.client_secret,
                    "https://www.googleapis.com/auth/drive.file",
                    CloudSyncManager.getInstance().getAuthPromptCallback());
            folders.getSimpleSyncFolderId();
            SimpleSync.LOGGER.info("[SimpleSync] Authenticated with Google Drive");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Auth interrupted", e);
        } finally {
            isAuthenticating.set(false);
        }
    }

    @Override
    public WorldMetadata upload(String worldName, Path archiveFile) throws IOException {
        return RetryUtil.retry(3, "Upload", () -> uploadOnce(worldName, archiveFile));
    }

    @Override
    public void download(String worldName, Path outputArchive) throws IOException {
        RetryUtil.retryVoid(3, "Download", () -> downloadOnce(worldName, outputArchive));
    }

    @Override
    public List<WorldMetadata> listWorlds() throws IOException {
        folders.getSimpleSyncFolderId();
        folders.clearCache();

        List<WorldMetadata> worlds = new ArrayList<>();
        String worldsFolderId = folders.getWorldsFolderId();
        String rootId = folders.getSimpleSyncFolderId();
        String query = DriveApiClient.buildQuery(
                "('%s' in parents or '%s' in parents) and trashed=false", worldsFolderId, rootId);

        Set<String> seenNames = new HashSet<>();
        List<String> idsToMigrate = new ArrayList<>();
        String pageToken = null;

        do {
            String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query)
                    + "&fields=nextPageToken,files(id,name,parents,modifiedTime,size)&pageSize=1000&orderBy=modifiedTime%20desc"
                    + (pageToken != null ? "&pageToken=" + RetryUtil.urlEncode(pageToken) : "");

            HttpRequest.Builder req = api.authedRequest(url, Duration.ofSeconds(30)).GET();
            HttpResponse<String> resp = api.send(req, 3);
            if (resp.statusCode() != 200) {
                throw new IOException("Failed to list files: HTTP " + resp.statusCode());
            }

            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray files = body.getAsJsonArray("files");
            if (files == null || files.size() == 0) break;

            for (int i = 0; i < files.size(); i++) {
                JsonObject file = files.get(i).getAsJsonObject();
                String mimeType = file.has("mimeType") ? file.get("mimeType").getAsString() : "";
                if ("application/vnd.google-apps.folder".equals(mimeType)) continue;

                String fileName = file.has("name") ? file.get("name").getAsString() : null;
                if (fileName == null || (!fileName.endsWith(".tar.zst") && !fileName.endsWith(".zip"))) continue;

                String wName = fileName.endsWith(".tar.zst")
                        ? fileName.substring(0, fileName.length() - 8)
                        : fileName.substring(0, fileName.length() - 4);
                if (!WorldSyncTask.isWorldNameSafe(wName)) continue;

                String fileId = file.has("id") ? file.get("id").getAsString() : null;
                if (fileId != null) {
                    folders.cacheFileId(fileName, fileId);
                    // Collect files in root to auto-migrate to Worlds subfolder after pagination
                    if (file.has("parents")) {
                        JsonArray parents = file.getAsJsonArray("parents");
                        for (int p = 0; p < parents.size(); p++) {
                            if (rootId.equals(parents.get(p).getAsString())) {
                                idsToMigrate.add(fileId);
                                break;
                            }
                        }
                    }
                }

                if (!seenNames.add(wName)) continue;

                long mtime = file.has("modifiedTime")
                        ? Instant.parse(file.get("modifiedTime").getAsString()).toEpochMilli()
                        : System.currentTimeMillis();
                long size = file.has("size") ? file.get("size").getAsLong() : 0L;
                worlds.add(new WorldMetadata(wName, mtime, size, fileId));
            }

            pageToken = body.has("nextPageToken") ? body.get("nextPageToken").getAsString() : null;
        } while (pageToken != null);

        for (String fileId : idsToMigrate) {
            try {
                moveFileToFolder(fileId, rootId, worldsFolderId);
            } catch (Exception e) {
                SimpleSync.LOGGER.warn("[SimpleSync] Failed to auto-migrate file {} to Worlds folder", fileId, e);
            }
        }

        return worlds;
    }

    @Override
    public WorldMetadata getWorldMetadata(String worldName) throws IOException {
        validateWorldName(worldName);
        ArchiveFileIds ids = findBothFileIds(worldName);
        if (ids.tarZstId() == null && ids.zipId() == null) return null;

        if (ids.tarZstId() != null && ids.zipId() != null) {
            WorldMetadata tarMeta = getFileMetadataById(ids.tarZstId(), worldName);
            WorldMetadata zipMeta = getFileMetadataById(ids.zipId(), worldName);
            if (tarMeta != null && zipMeta != null && zipMeta.lastModified() > tarMeta.lastModified()) {
                return zipMeta;
            }
            return tarMeta != null ? tarMeta : zipMeta;
        }
        return getFileMetadataById(ids.tarZstId() != null ? ids.tarZstId() : ids.zipId(), worldName);
    }

    @Override
    public void delete(String worldName) throws IOException {
        validateWorldName(worldName);
        ArchiveFileIds ids = findBothFileIds(worldName);
        if (ids.tarZstId() != null) { deleteFileById(ids.tarZstId()); folders.removeCachedFileId(worldName + ".tar.zst"); }
        if (ids.zipId() != null) { deleteFileById(ids.zipId()); folders.removeCachedFileId(worldName + ".zip"); }
    }

    @Override
    public synchronized void disconnect() throws IOException {
        folders.invalidateRoot();
        folders.clearCache();
        if (Files.isSymbolicLink(credentialsDir)) {
            throw new IOException("Refusing to clear symlinked credentials directory");
        }
        TokenStore.clear();
        SimpleSync.LOGGER.info("[SimpleSync] Disconnected from Google Drive");
    }

    @Override
    public void syncSchematics(Path gameRootDir) throws IOException {
        syncEngine.syncSchematics(gameRootDir);
    }

    @Override
    public void syncMasaConfigs(Path gameRootDir) throws IOException {
        syncEngine.syncMasaConfigs(gameRootDir);
    }

    // ─── Private: Upload ──────────────────────────────────────────────────

    private WorldMetadata uploadOnce(String worldName, Path archiveFile) throws IOException {
        validateWorldName(worldName);
        folders.getSimpleSyncFolderId();

        boolean isZip = archiveFile.getFileName().toString().endsWith(".zip");
        String fileName = worldName + (isZip ? ".zip" : ".tar.zst");
        String mimeType = isZip ? ZIP_MIME : TAR_ZST_MIME;
        long fileSize = Files.size(archiveFile);

        String existingFileId = folders.findFileId(fileName);
        boolean isUpdate = existingFileId != null;

        String initUrl = isUpdate
                ? "https://www.googleapis.com/upload/drive/v3/files/" + existingFileId + "?uploadType=resumable&fields=id,name,modifiedTime,size"
                : "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,modifiedTime,size";

        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", fileName);
        if (!isUpdate) {
            JsonArray parents = new JsonArray();
            parents.add(folders.getWorldsFolderId());
            metadata.add("parents", parents);
        }

        HttpRequest.Builder initReq = api.authedRequest(initUrl, Duration.ofSeconds(30))
                .method(isUpdate ? "PATCH" : "POST", HttpRequest.BodyPublishers.ofString(metadata.toString()))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", String.valueOf(fileSize));

        HttpResponse<String> initResp = api.send(initReq, 3);
        if (initResp.statusCode() != 200 && initResp.statusCode() != 201) {
            if (isUpdate && initResp.statusCode() == 404) {
                folders.removeCachedFileId(fileName);
                // Fall back to creating a new file without recursive calls
                isUpdate = false;
                initUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,modifiedTime,size";
                metadata = new JsonObject();
                metadata.addProperty("name", fileName);
                JsonArray parents = new JsonArray();
                parents.add(folders.getWorldsFolderId());
                metadata.add("parents", parents);

                initReq = api.authedRequest(initUrl, Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(metadata.toString()))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("X-Upload-Content-Type", mimeType)
                        .header("X-Upload-Content-Length", String.valueOf(fileSize));
                initResp = api.send(initReq, 3);
                if (initResp.statusCode() != 200 && initResp.statusCode() != 201) {
                    throw new IOException("Resumable upload init failed after 404 fallback: HTTP " + initResp.statusCode());
                }
            } else {
                throw new IOException("Resumable upload init failed: HTTP " + initResp.statusCode());
            }
        }

        Optional<String> location = initResp.headers().firstValue("Location");
        if (location.isEmpty()) throw new IOException("No Location header in resumable upload response");

        HttpResponse<String> putResp = uploadPut(location.get(), archiveFile, worldName, fileSize);
        if (putResp.statusCode() != 200 && putResp.statusCode() != 201) {
            throw new IOException("Upload PUT failed: HTTP " + putResp.statusCode());
        }

        JsonObject uploaded = JsonParser.parseString(putResp.body()).getAsJsonObject();
        String fileId = uploaded.get("id").getAsString();
        folders.cacheFileId(fileName, fileId);

        // Delete obsolete opposite format
        cleanupObsoleteFormat(worldName, isZip, fileId);

        long mtime = uploaded.has("modifiedTime")
                ? Instant.parse(uploaded.get("modifiedTime").getAsString()).toEpochMilli()
                : System.currentTimeMillis();
        long size = uploaded.has("size") ? uploaded.get("size").getAsLong() : fileSize;
        return new WorldMetadata(worldName, mtime, size, fileId);
    }

    private HttpResponse<String> uploadPut(String sessionUrl, Path file, String worldName, long fileSize)
            throws IOException {
        long offset = 0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (offset > 0 || attempt > 1) {
                    HttpRequest statusReq = HttpRequest.newBuilder(java.net.URI.create(sessionUrl))
                            .PUT(HttpRequest.BodyPublishers.noBody())
                            .header("Content-Range", "bytes */" + fileSize)
                            .timeout(Duration.ofSeconds(30)).build();
                    HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
                    if (statusResp.statusCode() == 308) {
                        String range = statusResp.headers().firstValue("Range").orElse("");
                        if (range.startsWith("bytes=0-")) {
                            try { offset = Long.parseLong(range.substring(8)) + 1; } catch (NumberFormatException ignored) {}
                        }
                    } else if (statusResp.statusCode() == 200 || statusResp.statusCode() == 201) {
                        return statusResp;
                    }
                }

                final long curOffset = offset;
                final long remaining = fileSize - curOffset;
                java.util.function.Supplier<InputStream> supplier = () -> {
                    try {
                        InputStream fis = Files.newInputStream(file);
                        if (curOffset > 0) {
                            long remainingToSkip = curOffset;
                            while (remainingToSkip > 0) {
                                long skipped = fis.skip(remainingToSkip);
                                if (skipped <= 0) {
                                    if (fis.read() == -1) {
                                        fis.close();
                                        throw new IOException("Unexpected EOF while seeking to offset " + curOffset);
                                    }
                                    remainingToSkip--;
                                } else {
                                    remainingToSkip -= skipped;
                                }
                            }
                        }
                        return new ProgressInputStream(fis, fileSize, worldName, true, curOffset);
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                };

                var delegate = HttpRequest.BodyPublishers.ofInputStream(supplier);
                HttpRequest.BodyPublisher bodyPub = new HttpRequest.BodyPublisher() {
                    @Override public long contentLength() { return remaining; }
                    @Override public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> s) { delegate.subscribe(s); }
                };

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(java.net.URI.create(sessionUrl))
                        .PUT(bodyPub).timeout(Duration.ofMinutes(15));
                if (curOffset > 0) {
                    reqBuilder.header("Content-Range", "bytes " + curOffset + "-" + (fileSize - 1) + "/" + fileSize);
                }

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 || resp.statusCode() == 201) return resp;
                if (resp.statusCode() == 308 && attempt < 3) continue;
                if (resp.statusCode() >= 500 && attempt < 3) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Upload PUT interrupted during sleep", ie);
                    }
                    continue;
                }
                return resp;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Upload PUT interrupted", e);
            } catch (IOException e) {
                if (attempt == 3) throw e;
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Upload PUT interrupted during sleep", ie);
                }
            }
        }
        throw new IOException("Upload PUT failed after retries");
    }

    // ─── Private: Download ────────────────────────────────────────────────

    private void downloadOnce(String worldName, Path outputArchive) throws IOException {
        validateWorldName(worldName);
        WorldMetadata meta = getWorldMetadata(worldName);
        if (meta == null || meta.cloudFileId() == null) {
            throw new IOException("World not found in cloud: " + worldName);
        }

        Files.createDirectories(outputArchive.getParent());
        if (Files.isSymbolicLink(outputArchive)) {
            throw new IOException("Refusing to write through symlink: " + outputArchive);
        }
        Files.deleteIfExists(outputArchive);

        String url = "https://www.googleapis.com/drive/v3/files/" + meta.cloudFileId() + "?alt=media";
        HttpRequest.Builder req = api.authedRequest(url, Duration.ofMinutes(15)).GET();

        try {
            HttpResponse<InputStream> resp = api.send(req, HttpResponse.BodyHandlers.ofInputStream(), 3);
            if (resp.statusCode() != 200) throw new IOException("Download failed: HTTP " + resp.statusCode());

            try (InputStream is = new ProgressInputStream(resp.body(), meta.sizeBytes(), worldName, false);
                 OutputStream os = new BufferedOutputStream(
                         Files.newOutputStream(outputArchive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 262144)) {
                is.transferTo(os);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(outputArchive); } catch (IOException ignored) {}
            if (e instanceof IOException ioe && e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw ioe;
            }
            throw new IOException("Download failed: " + e.getMessage(), e);
        }
    }

    // ─── Private: Helpers ─────────────────────────────────────────────────

    private ArchiveFileIds findBothFileIds(String worldName) throws IOException {
        return new ArchiveFileIds(folders.findFileId(worldName + ".tar.zst"), folders.findFileId(worldName + ".zip"));
    }

    private WorldMetadata getFileMetadataById(String fileId, String worldName) throws IOException {
        HttpRequest.Builder req = api.authedRequest(
                "https://www.googleapis.com/drive/v3/files/" + fileId + "?fields=id,name,modifiedTime,size",
                Duration.ofSeconds(30)).GET();
        HttpResponse<String> resp = api.send(req, 3);
        if (resp.statusCode() == 200) {
            JsonObject f = JsonParser.parseString(resp.body()).getAsJsonObject();
            long mtime = f.has("modifiedTime") ? Instant.parse(f.get("modifiedTime").getAsString()).toEpochMilli() : System.currentTimeMillis();
            long size = f.has("size") ? f.get("size").getAsLong() : 0L;
            return new WorldMetadata(worldName, mtime, size, fileId);
        } else if (resp.statusCode() == 404) {
            return null;
        }
        throw new IOException("Failed to get metadata: HTTP " + resp.statusCode());
    }

    private void deleteFileById(String fileId) throws IOException {
        HttpRequest.Builder req = api.authedRequest(
                "https://www.googleapis.com/drive/v3/files/" + fileId, Duration.ofSeconds(30)).DELETE();
        HttpResponse<String> resp = api.send(req, 3);
        if (resp.statusCode() != 204 && resp.statusCode() != 404) {
            throw new IOException("Delete failed: HTTP " + resp.statusCode());
        }
    }

    private void moveFileToFolder(String fileId, String fromFolderId, String toFolderId) {
        try {
            String url = "https://www.googleapis.com/drive/v3/files/" + fileId
                    + "?addParents=" + RetryUtil.urlEncode(toFolderId)
                    + "&removeParents=" + RetryUtil.urlEncode(fromFolderId) + "&fields=id,parents";
            HttpRequest.Builder req = api.authedRequest(url, Duration.ofSeconds(15))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody());
            api.send(req, 2);
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Failed to migrate file {}: {}", fileId, e.getMessage());
        }
    }

    private void cleanupObsoleteFormat(String worldName, boolean isZip, String newFileId) {
        try {
            String oldName = worldName + (isZip ? ".tar.zst" : ".zip");
            String oldId = folders.findFileId(oldName);
            if (oldId != null && !oldId.equals(newFileId)) {
                deleteFileById(oldId);
                folders.removeCachedFileId(oldName);
            }
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Could not clean obsolete archive: {}", e.getMessage());
        }
    }

    private void validateWorldName(String worldName) throws IOException {
        if (!WorldSyncTask.isWorldNameSafe(worldName)) {
            throw new IOException("Invalid world name: " + worldName);
        }
    }
}
