package dev.simplesync.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.SyncStatus;
import dev.simplesync.sync.WorldMetadata;
import dev.simplesync.sync.WorldSyncTask;
import dev.simplesync.util.RetryUtil;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Google Drive implementation of CloudProvider using Java's native HttpClient and REST API.
 * Uses OAuth2 Device Auth and stores files in a dedicated folder.
 */
public class GoogleDriveProvider implements CloudProvider {

    private static final Gson GSON = new Gson();
    private static final String SIMPLESYNC_FOLDER_NAME = "SimpleSync";
    private static final String ZIP_MIME_TYPE = "application/zip";
    private static final String TAR_ZST_MIME_TYPE = "application/zstd";
    private static final Pattern DRIVE_FILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");

    private final Path credentialsDir;
    private final Map<String, java.util.Optional<String>> fileIdCache = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private volatile String simpleSyncFolderId;
    private final AtomicBoolean isAuthenticating = new AtomicBoolean(false);
    private volatile ClientSecrets cachedSecrets;
    private volatile long lastSecretsMtime = -1;

    private record ArchiveFileIds(String tarZstId, String zipId) {}

    public GoogleDriveProvider() {
        this.credentialsDir = SyncConfig.getConfigDir().resolve("credentials");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .executor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "SimpleSync-HTTP");
                    t.setDaemon(true);
                    return t;
                }))
                .build();
    }

    @Override
    public String getName() {
        return "Google Drive";
    }

    @Override
    public boolean isAuthenticated() {
        try {
            TokenStore.TokenData tokens = TokenStore.load();
            return tokens != null && tokens.refreshToken != null && !tokens.refreshToken.isEmpty();
        } catch (Exception e) {
            SimpleSync.LOGGER.error("[SimpleSync] Error checking authentication status", e);
            return false;
        }
    }

    @Override
    public boolean isAuthenticating() {
        return isAuthenticating.get();
    }

    @Override
    public void authenticate() throws IOException {
        if (isAuthenticated()) {
            return;
        }
        if (!isAuthenticating.compareAndSet(false, true)) {
            throw new IOException("Authentication is already in progress.");
        }
        try {
            ClientSecrets secrets = loadClientSecrets();
            if (secrets == null) {
                throw new IOException("client_secret.json is missing! Please place your Google OAuth2 client_secret.json in the config/simplesync/ folder.");
            }
            ClientSecrets.Details details = secrets.getDetails();

            new DeviceCodeAuthenticator().authenticate(
                    details.client_id,
                    details.client_secret,
                    "https://www.googleapis.com/auth/drive.file",
                    CloudSyncManager.getInstance().getAuthPromptCallback()
            );

            ensureSimpleSyncFolder();
            SimpleSync.LOGGER.info("[SimpleSync] Successfully authenticated with Google Drive");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Authentication flow was interrupted", e);
        } finally {
            isAuthenticating.set(false);
        }
    }

    @Override
    public WorldMetadata upload(String worldName, Path zipFile) throws IOException {
        return RetryUtil.retry(3, "Upload", () -> uploadOnce(worldName, zipFile));
    }

    private WorldMetadata uploadOnce(String worldName, Path zipFile) throws IOException {
        validateWorldName(worldName);
        ensureSimpleSyncFolder();

        boolean isZip = zipFile.getFileName().toString().endsWith(".zip");
        String fileName = worldName + (isZip ? ".zip" : ".tar.zst");
        String mimeType = isZip ? ZIP_MIME_TYPE : TAR_ZST_MIME_TYPE;
        long fileSize = Files.size(zipFile);

        SimpleSync.LOGGER.info("[SimpleSync] Uploading {} to Google Drive...", fileName);

        String existingFileId = findFileId(fileName);
        boolean isUpdate = existingFileId != null;

        String accessToken = ensureValidAccessToken();
        String initUrl = isUpdate
                ? "https://www.googleapis.com/upload/drive/v3/files/" + existingFileId + "?uploadType=resumable&fields=id,name,modifiedTime,size"
                : "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,modifiedTime,size";

        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", fileName);
        if (!isUpdate) {
            String worldsFolderId = getWorldsFolderId();
            JsonArray parents = new JsonArray();
            parents.add(worldsFolderId);
            metadata.add("parents", parents);
        }

        String method = isUpdate ? "PATCH" : "POST";
        HttpRequest.Builder initReqBuilder = authedRequest(accessToken, initUrl, Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.ofString(metadata.toString()))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", String.valueOf(fileSize));

        HttpResponse<String> initResponse = sendWithRetry(initReqBuilder, 3);

        if (initResponse.statusCode() != 200 && initResponse.statusCode() != 201) {
            if (isUpdate && initResponse.statusCode() == 404) {
                SimpleSync.LOGGER.warn("[SimpleSync] Google Drive file ID {} not found. Falling back to create...", existingFileId);
                fileIdCache.remove(fileName);
                return uploadOnce(worldName, zipFile);
            }
            throw new IOException("Failed to initialize resumable upload: HTTP " + initResponse.statusCode() + " - " + initResponse.body());
        }

        Optional<String> location = initResponse.headers().firstValue("Location");
        if (location.isEmpty()) {
            throw new IOException("Google Drive response did not contain Location header for resumable upload.");
        }
        String sessionUrl = location.get();

        HttpResponse<String> putResponse;
        try {
            putResponse = uploadPut(sessionUrl, zipFile, worldName, fileSize);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload PUT interrupted", e);
        }

        if (putResponse.statusCode() != 200 && putResponse.statusCode() != 201) {
            throw new IOException("Resumable upload PUT failed: HTTP " + putResponse.statusCode() + " - " + putResponse.body());
        }

        JsonObject uploadedFile = JsonParser.parseString(putResponse.body()).getAsJsonObject();
        String fileId = uploadedFile.get("id").getAsString();
        fileIdCache.put(fileName, java.util.Optional.ofNullable(fileId));

        // Delete obsolete other compression formats of this world
        try {
            String oldOppositeName = worldName + (isZip ? ".tar.zst" : ".zip");
            String oldOppositeId = findFileId(oldOppositeName);
            if (oldOppositeId != null && !oldOppositeId.equals(fileId)) {
                deleteFileById(accessToken, oldOppositeId);
                fileIdCache.remove(oldOppositeName);
                SimpleSync.LOGGER.info("[SimpleSync] Deleted obsolete {} from Google Drive after uploading {}", oldOppositeName, fileName);
            }
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Could not clean up obsolete archive file from Google Drive: {}", e.getMessage());
        }

        long mtime = uploadedFile.has("modifiedTime")
                ? java.time.Instant.parse(uploadedFile.get("modifiedTime").getAsString()).toEpochMilli()
                : System.currentTimeMillis();
        long size = uploadedFile.has("size") ? uploadedFile.get("size").getAsLong() : fileSize;

        return new WorldMetadata(worldName, mtime, size, fileId);
    }

    private HttpResponse<String> uploadPut(String sessionUrl, Path file, String worldName, long fileSize) throws IOException, InterruptedException {
        long offset = 0;
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (offset > 0 || attempt > 1) {
                    HttpRequest statusReq = HttpRequest.newBuilder(URI.create(sessionUrl))
                            .PUT(HttpRequest.BodyPublishers.noBody())
                            .header("Content-Range", "bytes */" + fileSize)
                            .timeout(Duration.ofSeconds(30))
                            .build();
                    HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
                    if (statusResp.statusCode() == 308) {
                        String rangeHeader = statusResp.headers().firstValue("Range").orElse("");
                        if (rangeHeader.startsWith("bytes=0-")) {
                            try {
                                long lastByte = Long.parseLong(rangeHeader.substring(8));
                                offset = lastByte + 1;
                            } catch (NumberFormatException ignored) {}
                        }
                    } else if (statusResp.statusCode() == 200 || statusResp.statusCode() == 201) {
                        return statusResp;
                    }
                }

                final long currentOffset = offset;
                final long remaining = fileSize - currentOffset;
                java.util.function.Supplier<InputStream> supplier = () -> {
                    try {
                        InputStream fis = Files.newInputStream(file);
                        if (currentOffset > 0) {
                            fis.skip(currentOffset);
                        }
                        return new ProgressInputStream(fis, fileSize, worldName, true, currentOffset);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                };

                HttpRequest.BodyPublisher delegate = HttpRequest.BodyPublishers.ofInputStream(supplier);
                HttpRequest.BodyPublisher bodyPublisher = new HttpRequest.BodyPublisher() {
                    @Override
                    public long contentLength() {
                        return remaining;
                    }

                    @Override
                    public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                        delegate.subscribe(subscriber);
                    }
                };

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(sessionUrl))
                        .PUT(bodyPublisher)
                        .timeout(Duration.ofMinutes(15));
                if (currentOffset > 0) {
                    reqBuilder.header("Content-Range", "bytes " + currentOffset + "-" + (fileSize - 1) + "/" + fileSize);
                }
                HttpRequest request = reqBuilder.build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    return response;
                }
                if (response.statusCode() == 308 && attempt < maxRetries) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Upload incomplete (HTTP 308), retrying chunk...");
                    continue;
                }
                if (response.statusCode() >= 500 && attempt < maxRetries) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Server error {} during upload, retrying chunk...", response.statusCode());
                    Thread.sleep(2000L * attempt);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw e;
                }
                SimpleSync.LOGGER.warn("[SimpleSync] IOException during upload PUT (attempt {}/{}): {}. Querying resume status...", attempt, maxRetries, e.getMessage());
                Thread.sleep(2000L * attempt);
            }
        }
        throw new IOException("Upload PUT failed after retries");
    }

    @Override
    public void download(String worldName, Path outputArchive) throws IOException {
        RetryUtil.retryVoid(3, "Download", () -> downloadOnce(worldName, outputArchive));
    }

    private void downloadOnce(String worldName, Path outputArchive) throws IOException {
        validateWorldName(worldName);

        WorldMetadata meta = getWorldMetadata(worldName);
        if (meta == null || meta.cloudFileId() == null) {
            throw new IOException("World not found in cloud: " + worldName);
        }

        final String targetFileId = meta.cloudFileId();
        final long fileSize = meta.sizeBytes();
        SimpleSync.LOGGER.info("[SimpleSync] Downloading world '{}' (ID: {}) from Google Drive...", worldName, targetFileId);

        Files.createDirectories(outputArchive.getParent());
        if (Files.isSymbolicLink(outputArchive)) {
            throw new IOException("Refusing to write download through symbolic link: " + outputArchive);
        }

        Files.deleteIfExists(outputArchive);

        String accessToken = ensureValidAccessToken();
        HttpRequest.Builder reqBuilder = authedRequest(accessToken, "https://www.googleapis.com/drive/v3/files/" + targetFileId + "?alt=media", Duration.ofMinutes(15))
                .GET();

        try {
            HttpResponse<InputStream> response = sendWithRetry(reqBuilder, HttpResponse.BodyHandlers.ofInputStream(), 3);
            if (response.statusCode() != 200) {
                throw new IOException("Download failed: HTTP " + response.statusCode());
            }
            try (InputStream is = new ProgressInputStream(response.body(), fileSize, worldName, false);
                 OutputStream os = new BufferedOutputStream(Files.newOutputStream(outputArchive, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE), 262144)) {
                byte[] buffer = new byte[262144];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(outputArchive); } catch (IOException ignored) {}
            if (e instanceof IOException ioe && e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw ioe;
            }
            throw new IOException("Download failed: " + e.getMessage(), e);
        }

        SimpleSync.LOGGER.info("[SimpleSync] Download complete: {}", outputArchive);
    }

    @Override
    public List<WorldMetadata> listWorlds() throws IOException {
        ensureSimpleSyncFolder();

        List<WorldMetadata> worlds = new ArrayList<>();
        fileIdCache.clear();

        String worldsFolderId = getWorldsFolderId();
        String query = buildQuery("('%s' in parents or '%s' in parents) and trashed=false", worldsFolderId, simpleSyncFolderId);
        java.util.Set<String> seenWorldNames = new java.util.HashSet<>();
        String pageToken = null;
        String accessToken = ensureValidAccessToken();

        do {
            String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query)
                    + "&fields=nextPageToken,files(id,name,parents,modifiedTime,size)&pageSize=1000"
                    + "&orderBy=modifiedTime%20desc"
                    + (pageToken != null ? "&pageToken=" + RetryUtil.urlEncode(pageToken) : "");

            HttpRequest.Builder reqBuilder = authedRequest(accessToken, url, Duration.ofSeconds(30))
                    .GET();

            HttpResponse<String> response = sendWithRetry(reqBuilder, 3);
            if (response.statusCode() != 200) {
                throw new IOException("Failed to list files: HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray files = body.getAsJsonArray("files");

            if (files == null || files.size() == 0) {
                break;
            }

            for (int i = 0; i < files.size(); i++) {
                JsonObject file = files.get(i).getAsJsonObject();
                String mimeType = file.has("mimeType") ? file.get("mimeType").getAsString() : "";
                if ("application/vnd.google-apps.folder".equals(mimeType)) {
                    continue;
                }
                String fileName = file.has("name") ? file.get("name").getAsString() : null;
                if (fileName == null || (!fileName.endsWith(".tar.zst") && !fileName.endsWith(".zip"))) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Ignoring Google Drive file with unexpected name: {}", fileName);
                    continue;
                }
                String worldName = fileName.endsWith(".tar.zst")
                        ? fileName.substring(0, fileName.length() - ".tar.zst".length())
                        : fileName.substring(0, fileName.length() - ".zip".length());
                if (!WorldSyncTask.isWorldNameSafe(worldName)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Ignoring Google Drive file with unsafe world name: {}", fileName);
                    continue;
                }
                
                String fileId = file.has("id") ? file.get("id").getAsString() : null;
                if (fileId != null) {
                    fileIdCache.put(fileName, java.util.Optional.of(fileId));
                    if (file.has("parents")) {
                        JsonArray parents = file.getAsJsonArray("parents");
                        for (int p = 0; p < parents.size(); p++) {
                            if (simpleSyncFolderId.equals(parents.get(p).getAsString())) {
                                moveFileToFolder(accessToken, fileId, simpleSyncFolderId, worldsFolderId);
                                break;
                            }
                        }
                    }
                }
                if (seenWorldNames.contains(worldName)) {
                    continue; 
                }
                seenWorldNames.add(worldName);

                long modifiedTime = file.has("modifiedTime")
                        ? java.time.Instant.parse(file.get("modifiedTime").getAsString()).toEpochMilli()
                        : System.currentTimeMillis();
                long size = file.has("size") ? file.get("size").getAsLong() : 0L;

                worlds.add(new WorldMetadata(
                        worldName,
                        modifiedTime,
                        size,
                        fileId
                ));
            }

            pageToken = body.has("nextPageToken") ? body.get("nextPageToken").getAsString() : null;
        } while (pageToken != null);

        return worlds;
    }

    @Override
    public WorldMetadata getWorldMetadata(String worldName) throws IOException {
        validateWorldName(worldName);

        ArchiveFileIds ids = findBothFileIds(worldName);

        if (ids.tarZstId() == null && ids.zipId() == null) {
            return null;
        }

        if (ids.tarZstId() != null && ids.zipId() != null) {
            WorldMetadata tarZstMetadata = getFileMetadataById(ids.tarZstId(), worldName);
            WorldMetadata zipMetadata = getFileMetadataById(ids.zipId(), worldName);
            if (tarZstMetadata != null && zipMetadata != null) {
                if (zipMetadata.lastModified() > tarZstMetadata.lastModified()) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Both .tar.zst and legacy .zip exist for world '{}'. Selecting newer .zip archive.", worldName);
                    return zipMetadata;
                }
            }
            SimpleSync.LOGGER.warn("[SimpleSync] Both .tar.zst and legacy .zip exist for world '{}'. Defaulting to .tar.zst archive.", worldName);
            return tarZstMetadata != null ? tarZstMetadata : zipMetadata;
        } else {
            String targetFileId = (ids.tarZstId() != null) ? ids.tarZstId() : ids.zipId();
            return getFileMetadataById(targetFileId, worldName);
        }
    }

    private WorldMetadata getFileMetadataById(String fileId, String worldName) throws IOException {
        String accessToken = ensureValidAccessToken();
        HttpRequest.Builder requestBuilder = authedRequest(accessToken, "https://www.googleapis.com/drive/v3/files/" + fileId + "?fields=id,name,modifiedTime,size", Duration.ofSeconds(30))
                .GET();
        
        HttpResponse<String> response = sendWithRetry(requestBuilder, 3);
        if (response.statusCode() == 200) {
            JsonObject file = JsonParser.parseString(response.body()).getAsJsonObject();
            long modifiedTime = file.has("modifiedTime")
                    ? java.time.Instant.parse(file.get("modifiedTime").getAsString()).toEpochMilli()
                    : System.currentTimeMillis();
            long size = file.has("size") ? file.get("size").getAsLong() : 0L;
            return new WorldMetadata(worldName, modifiedTime, size, fileId);
        } else if (response.statusCode() == 404) {
            return null;
        } else {
            throw new IOException("Failed to get file metadata: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    @Override
    public void delete(String worldName) throws IOException {
        validateWorldName(worldName);
        
        String accessToken = ensureValidAccessToken();
        ArchiveFileIds ids = findBothFileIds(worldName);

        if (ids.tarZstId() != null) {
            deleteFileById(accessToken, ids.tarZstId());
            fileIdCache.remove(worldName + ".tar.zst");
            SimpleSync.LOGGER.info("[SimpleSync] Deleted {} from Google Drive", worldName + ".tar.zst");
        }

        if (ids.zipId() != null) {
            deleteFileById(accessToken, ids.zipId());
            fileIdCache.remove(worldName + ".zip");
            SimpleSync.LOGGER.info("[SimpleSync] Deleted {} from Google Drive", worldName + ".zip");
        }
    }

    private void deleteFileById(String accessToken, String fileId) throws IOException {
        HttpRequest.Builder reqBuilder = authedRequest(accessToken, "https://www.googleapis.com/drive/v3/files/" + fileId, Duration.ofSeconds(30))
                .DELETE();
        HttpResponse<String> response = sendWithRetry(reqBuilder, 3);
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new IOException("Failed to delete file " + fileId + ": HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    @Override
    public synchronized void disconnect() throws IOException {
        simpleSyncFolderId = null;
        fileIdCache.clear();
        if (Files.isSymbolicLink(credentialsDir)) {
            throw new IOException("Refusing to clear symlinked credentials directory: " + credentialsDir);
        }
        TokenStore.clear();
        SimpleSync.LOGGER.info("[SimpleSync] Disconnected from Google Drive and cleared stored credentials");
    }

    // ─── Subfolder & Incremental Sync ─────────────────────────────────────────

    public synchronized String getOrCreateSubfolder(String parentFolderId, String folderName) throws IOException {
        String accessToken = ensureValidAccessToken();
        String query = buildQuery("name='%s' and '%s' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false", folderName, parentFolderId);
        String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query) + "&fields=files(id)&pageSize=1";

        HttpRequest.Builder searchReqBuilder = authedRequest(accessToken, url, Duration.ofSeconds(30)).GET();
        HttpResponse<String> searchResp = sendWithRetry(searchReqBuilder, 3);
        if (searchResp.statusCode() == 200) {
            JsonObject body = JsonParser.parseString(searchResp.body()).getAsJsonObject();
            JsonArray files = body.getAsJsonArray("files");
            if (files != null && files.size() > 0) {
                return files.get(0).getAsJsonObject().get("id").getAsString();
            }
        }

        JsonObject folderMeta = new JsonObject();
        folderMeta.addProperty("name", folderName);
        folderMeta.addProperty("mimeType", "application/vnd.google-apps.folder");
        JsonArray parents = new JsonArray();
        parents.add(parentFolderId);
        folderMeta.add("parents", parents);

        HttpRequest.Builder createReqBuilder = authedRequest(accessToken, "https://www.googleapis.com/drive/v3/files?fields=id", Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(folderMeta.toString()))
                .header("Content-Type", "application/json; charset=UTF-8");

        HttpResponse<String> createResp = sendWithRetry(createReqBuilder, 3);
        if (createResp.statusCode() == 200 || createResp.statusCode() == 201) {
            JsonObject folder = JsonParser.parseString(createResp.body()).getAsJsonObject();
            return folder.get("id").getAsString();
        }
        throw new IOException("Failed to create folder '" + folderName + "': HTTP " + createResp.statusCode() + " - " + createResp.body());
    }

    public String getWorldsFolderId() throws IOException {
        ensureSimpleSyncFolder();
        SyncConfig config = SyncConfig.load();
        if (config.worldsFolderId != null && isSafeDriveFileId(config.worldsFolderId)) {
            return config.worldsFolderId;
        }
        String id = getOrCreateSubfolder(simpleSyncFolderId, "Worlds");
        config.worldsFolderId = id;
        config.save();
        return id;
    }

    public String getSchematicsFolderId() throws IOException {
        ensureSimpleSyncFolder();
        SyncConfig config = SyncConfig.load();
        if (config.schematicsFolderId != null && isSafeDriveFileId(config.schematicsFolderId)) {
            return config.schematicsFolderId;
        }
        String id = getOrCreateSubfolder(simpleSyncFolderId, "Schematics");
        config.schematicsFolderId = id;
        config.save();
        return id;
    }

    public String getConfigsFolderId() throws IOException {
        ensureSimpleSyncFolder();
        SyncConfig config = SyncConfig.load();
        if (config.configsFolderId != null && isSafeDriveFileId(config.configsFolderId)) {
            return config.configsFolderId;
        }
        String id = getOrCreateSubfolder(simpleSyncFolderId, "Configs");
        config.configsFolderId = id;
        config.save();
        return id;
    }

    @Override
    public void syncSchematics(Path gameRootDir) throws IOException {
        Path schematicsDir = gameRootDir.resolve("schematics");
        Files.createDirectories(schematicsDir);
        String remoteFolderId = getSchematicsFolderId();
        List<dev.simplesync.sync.FolderSyncTask.LocalFileInfo> localFiles = dev.simplesync.sync.FolderSyncTask.scanLocalDirectory(schematicsDir);
        syncDirectoryIncremental(remoteFolderId, schematicsDir, localFiles);
    }

    @Override
    public void syncMasaConfigs(Path gameRootDir) throws IOException {
        String remoteFolderId = getConfigsFolderId();
        List<dev.simplesync.sync.FolderSyncTask.LocalFileInfo> localFiles = dev.simplesync.sync.FolderSyncTask.scanMasaConfigFiles(gameRootDir);
        syncDirectoryIncremental(remoteFolderId, gameRootDir, localFiles);
    }

    private record DriveItem(String id, String name, String parentId, String mimeType, long modifiedTime, long size) {}

    private void syncDirectoryIncremental(String rootRemoteFolderId, Path localBaseDir, List<dev.simplesync.sync.FolderSyncTask.LocalFileInfo> localFiles) throws IOException {
        String accessToken = ensureValidAccessToken();
        List<DriveItem> allRemoteItems = listAllDriveItemsUnder(accessToken, rootRemoteFolderId);

        SyncConfig config = SyncConfig.load();
        List<dev.simplesync.sync.FolderSyncTask.RemoteFileInfo> remoteFiles = reconstructRemoteFileInfos(allRemoteItems, rootRemoteFolderId);
        dev.simplesync.sync.FolderSyncTask.SyncPlan plan = dev.simplesync.sync.FolderSyncTask.createSyncPlan(localFiles, remoteFiles, config.fileTracking);

        Map<String, String> folderPathToIdMap = reconstructRemoteFolderMap(allRemoteItems, rootRemoteFolderId);
        folderPathToIdMap.put("", rootRemoteFolderId);

        for (dev.simplesync.sync.FolderSyncTask.LocalFileInfo local : plan.toUpload()) {
            uploadSingleFileIncremental(accessToken, rootRemoteFolderId, localBaseDir, local, folderPathToIdMap, remoteFiles);
            long now = System.currentTimeMillis();
            config.setFileTracking(local.relativePath(), new SyncConfig.FileTrackingInfo(now, local.size(), local.lastModified()));
            config.save();
        }

        for (dev.simplesync.sync.FolderSyncTask.RemoteFileInfo remote : plan.toDownload()) {
            downloadSingleFileIncremental(accessToken, localBaseDir, remote);
            long now = System.currentTimeMillis();
            long localMtime = remote.lastModified() > 0 ? remote.lastModified() : now;
            config.setFileTracking(remote.relativePath(), new SyncConfig.FileTrackingInfo(localMtime, remote.size(), localMtime));
            config.save();
        }
    }

    private void moveFileToFolder(String accessToken, String fileId, String fromFolderId, String toFolderId) {
        try {
            String url = "https://www.googleapis.com/drive/v3/files/" + fileId
                    + "?addParents=" + RetryUtil.urlEncode(toFolderId)
                    + "&removeParents=" + RetryUtil.urlEncode(fromFolderId)
                    + "&fields=id,parents";
            HttpRequest.Builder reqBuilder = authedRequest(accessToken, url, Duration.ofSeconds(15))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody());
            sendWithRetry(reqBuilder, 2);
            SimpleSync.LOGGER.info("[SimpleSync] Auto-migrated world file {} to Worlds subfolder in Google Drive", fileId);
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Failed to auto-migrate file {} to Worlds subfolder: {}", fileId, e.getMessage());
        }
    }

    private List<DriveItem> listAllDriveItemsUnder(String accessToken, String rootFolderId) throws IOException {
        List<DriveItem> result = new ArrayList<>();
        List<String> currentLevelFolderIds = List.of(rootFolderId);
        java.util.Set<String> visitedFolderIds = new java.util.HashSet<>();
        visitedFolderIds.add(rootFolderId);

        while (!currentLevelFolderIds.isEmpty()) {
            List<String> nextLevelFolderIds = new ArrayList<>();

            for (int i = 0; i < currentLevelFolderIds.size(); i += 30) {
                List<String> chunk = currentLevelFolderIds.subList(i, Math.min(i + 30, currentLevelFolderIds.size()));
                StringBuilder queryBuilder = new StringBuilder("(");
                for (int j = 0; j < chunk.size(); j++) {
                    if (j > 0) queryBuilder.append(" or ");
                    queryBuilder.append("'").append(escapeQueryString(chunk.get(j))).append("' in parents");
                }
                queryBuilder.append(") and trashed=false");

                String pageToken = null;
                do {
                    String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(queryBuilder.toString())
                            + "&fields=nextPageToken,files(id,name,parents,mimeType,modifiedTime,size)&pageSize=1000"
                            + (pageToken != null ? "&pageToken=" + RetryUtil.urlEncode(pageToken) : "");

                    HttpRequest.Builder reqBuilder = authedRequest(accessToken, url, Duration.ofSeconds(30)).GET();
                    HttpResponse<String> response = sendWithRetry(reqBuilder, 3);
                    if (response.statusCode() != 200) {
                        throw new IOException("Failed to list batch items: HTTP " + response.statusCode());
                    }

                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
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
                            if (f.has("parents")) {
                                JsonArray parents = f.getAsJsonArray("parents");
                                if (parents.size() > 0) {
                                    parentId = parents.get(0).getAsString();
                                }
                            }

                            result.add(new DriveItem(id, name, parentId, mimeType, mtime, size));
                            if ("application/vnd.google-apps.folder".equals(mimeType)) {
                                if (visitedFolderIds.add(id)) {
                                    nextLevelFolderIds.add(id);
                                }
                            }
                        }
                    }
                    pageToken = body.has("nextPageToken") ? body.get("nextPageToken").getAsString() : null;
                } while (pageToken != null);
            }

            currentLevelFolderIds = nextLevelFolderIds;
        }

        return result;
    }

    private List<dev.simplesync.sync.FolderSyncTask.RemoteFileInfo> reconstructRemoteFileInfos(List<DriveItem> items, String rootFolderId) {
        Map<String, DriveItem> itemMap = new HashMap<>();
        for (DriveItem item : items) {
            itemMap.put(item.id(), item);
        }

        List<dev.simplesync.sync.FolderSyncTask.RemoteFileInfo> result = new ArrayList<>();
        for (DriveItem item : items) {
            if ("application/vnd.google-apps.folder".equals(item.mimeType())) {
                continue;
            }
            String relPath = buildRelativePath(item, itemMap, rootFolderId);
            if (relPath != null) {
                result.add(new dev.simplesync.sync.FolderSyncTask.RemoteFileInfo(relPath, item.id(), item.modifiedTime(), item.size()));
            }
        }
        return result;
    }

    private Map<String, String> reconstructRemoteFolderMap(List<DriveItem> items, String rootFolderId) {
        Map<String, DriveItem> itemMap = new HashMap<>();
        for (DriveItem item : items) {
            itemMap.put(item.id(), item);
        }

        Map<String, String> result = new HashMap<>();
        for (DriveItem item : items) {
            if ("application/vnd.google-apps.folder".equals(item.mimeType())) {
                String relPath = buildRelativePath(item, itemMap, rootFolderId);
                if (relPath != null) {
                    result.put(relPath, item.id());
                }
            }
        }
        return result;
    }

    private String buildRelativePath(DriveItem item, Map<String, DriveItem> itemMap, String rootFolderId) {
        List<String> parts = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        DriveItem curr = item;
        while (curr != null && !curr.id().equals(rootFolderId)) {
            if (!visited.add(curr.id())) {
                return null;
            }
            parts.add(0, curr.name());
            String parentId = curr.parentId();
            if (parentId == null || parentId.equals(rootFolderId)) {
                break;
            }
            curr = itemMap.get(parentId);
        }
        return String.join("/", parts);
    }

    private void uploadSingleFileIncremental(String accessToken, String rootFolderId, Path localBaseDir, dev.simplesync.sync.FolderSyncTask.LocalFileInfo local, Map<String, String> folderPathToIdMap, List<dev.simplesync.sync.FolderSyncTask.RemoteFileInfo> remoteFiles) throws IOException {
        String relPath = local.relativePath();
        int lastSlash = relPath.lastIndexOf('/');
        String parentRelPath = lastSlash > 0 ? relPath.substring(0, lastSlash) : "";
        String fileName = lastSlash >= 0 ? relPath.substring(lastSlash + 1) : relPath;

        String parentFolderId = resolveOrCreateRemoteFolderPath(parentRelPath, rootFolderId, folderPathToIdMap);

        String existingFileId = null;
        for (dev.simplesync.sync.FolderSyncTask.RemoteFileInfo remote : remoteFiles) {
            if (remote.relativePath().equals(relPath)) {
                existingFileId = remote.fileId();
                break;
            }
        }

        if (existingFileId != null) {
            String url = "https://www.googleapis.com/upload/drive/v3/files/" + existingFileId + "?uploadType=media";
            HttpRequest.Builder reqBuilder = authedRequest(accessToken, url, Duration.ofSeconds(60))
                    .method("PATCH", HttpRequest.BodyPublishers.ofFile(local.fullPath()))
                    .header("Content-Type", "application/octet-stream");
            HttpResponse<String> response = sendWithRetry(reqBuilder, 3);
            if (response.statusCode() != 200) {
                throw new IOException("Failed to update file " + relPath + ": HTTP " + response.statusCode());
            }
        } else {
            byte[] fileBytes = Files.readAllBytes(local.fullPath());
            String url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart";
            String boundary = "SimpleSyncBoundary" + System.currentTimeMillis();

            JsonObject meta = new JsonObject();
            meta.addProperty("name", fileName);
            JsonArray parents = new JsonArray();
            parents.add(parentFolderId);
            meta.add("parents", parents);

            byte[] bodyBytes = buildMultipartBody(boundary, meta.toString(), fileBytes);

            HttpRequest.Builder reqBuilder = authedRequest(accessToken, url, Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .header("Content-Type", "multipart/related; boundary=" + boundary);

            HttpResponse<String> response = sendWithRetry(reqBuilder, 3);
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new IOException("Failed to upload new file " + relPath + ": HTTP " + response.statusCode());
            }
        }
    }

    private String resolveOrCreateRemoteFolderPath(String relFolderPath, String rootFolderId, Map<String, String> folderMap) throws IOException {
        if (relFolderPath == null || relFolderPath.isEmpty()) {
            return rootFolderId;
        }
        if (folderMap.containsKey(relFolderPath)) {
            return folderMap.get(relFolderPath);
        }

        String[] parts = relFolderPath.split("/");
        String currentRel = "";
        String currentParentId = rootFolderId;

        for (String part : parts) {
            currentRel = currentRel.isEmpty() ? part : currentRel + "/" + part;
            if (folderMap.containsKey(currentRel)) {
                currentParentId = folderMap.get(currentRel);
            } else {
                currentParentId = getOrCreateSubfolder(currentParentId, part);
                folderMap.put(currentRel, currentParentId);
            }
        }
        return currentParentId;
    }

    private byte[] buildMultipartBody(String boundary, String jsonMeta, byte[] fileBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String header1 = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + jsonMeta + "\r\n";
        String header2 = "--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        baos.write(header1.getBytes(StandardCharsets.UTF_8));
        baos.write(header2.getBytes(StandardCharsets.UTF_8));
        baos.write(fileBytes);
        baos.write(footer.getBytes(StandardCharsets.UTF_8));
        return baos.toByteArray();
    }

    private void downloadSingleFileIncremental(String accessToken, Path localBaseDir, dev.simplesync.sync.FolderSyncTask.RemoteFileInfo remote) throws IOException {
        Path targetFile = localBaseDir.resolve(remote.relativePath()).normalize();
        Path normalizedBase = localBaseDir.normalize();
        if (!targetFile.startsWith(normalizedBase)) {
            throw new SecurityException("Security violation: path traversal detected in remote file path: " + remote.relativePath());
        }
        Files.createDirectories(targetFile.getParent());

        String url = "https://www.googleapis.com/drive/v3/files/" + remote.fileId() + "?alt=media";
        HttpRequest.Builder reqBuilder = authedRequest(accessToken, url, Duration.ofSeconds(60)).GET();
        HttpResponse<InputStream> response = sendWithRetry(reqBuilder, HttpResponse.BodyHandlers.ofInputStream(), 3);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download file " + remote.relativePath() + ": HTTP " + response.statusCode());
        }

        try (InputStream is = response.body();
             OutputStream os = Files.newOutputStream(targetFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            is.transferTo(os);
        }

        if (remote.lastModified() > 0) {
            try {
                Files.setLastModifiedTime(targetFile, java.nio.file.attribute.FileTime.fromMillis(remote.lastModified()));
            } catch (IOException ignored) {}
        }
    }

    // ─── Private Helpers ────────────────────────────────────────────────────

    private ArchiveFileIds findBothFileIds(String worldName) throws IOException {
        String fileIdTarZst = findFileId(worldName + ".tar.zst");
        String fileIdZip = findFileId(worldName + ".zip");
        return new ArchiveFileIds(fileIdTarZst, fileIdZip);
    }

    private String buildQuery(String format, String... args) {
        Object[] escapedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            escapedArgs[i] = escapeQueryString(args[i]);
        }
        return String.format(format, escapedArgs);
    }

    public static class ClientSecrets {
        public Details installed;
        public Details web;

        public static class Details {
            public String client_id;
            public String client_secret;
        }

        public Details getDetails() {
            return installed != null ? installed : web;
        }
    }

    private synchronized ClientSecrets loadClientSecrets() throws IOException {
        Path clientSecretFile = SyncConfig.getConfigDir().resolve("client_secret.json");
        if (!Files.exists(clientSecretFile)) {
            SimpleSync.LOGGER.warn("[SimpleSync] No client_secret.json found at: {}", clientSecretFile);
            SimpleSync.LOGGER.warn("[SimpleSync] Please place your Google OAuth2 client_secret.json in the config/simplesync/ folder.");
            SimpleSync.LOGGER.warn("[SimpleSync] Instructions: https://console.cloud.google.com/apis/credentials");
            cachedSecrets = null;
            lastSecretsMtime = -1;
            return null;
        }
        try {
            long currentMtime = Files.getLastModifiedTime(clientSecretFile).toMillis();
            if (cachedSecrets != null && currentMtime == lastSecretsMtime) {
                return cachedSecrets;
            }
            String json = Files.readString(clientSecretFile);
            ClientSecrets secrets = GSON.fromJson(json, ClientSecrets.class);
            if (secrets == null || secrets.getDetails() == null) {
                throw new IOException("Invalid Google OAuth client secrets file: missing installed/web section");
            }
            cachedSecrets = secrets;
            lastSecretsMtime = currentMtime;
            return secrets;
        } catch (Exception e) {
            throw new IOException("Failed to load client_secret.json: " + e.getMessage(), e);
        }
    }

    private synchronized String ensureValidAccessToken() throws IOException {
        TokenStore.TokenData tokenData = TokenStore.load();
        if (tokenData == null) {
            throw new IOException("Google Drive is not authenticated. Please authenticate.");
        }
        
        if (System.currentTimeMillis() >= tokenData.expiresAtMs - 300000) {
            if (tokenData.refreshToken == null || tokenData.refreshToken.isEmpty()) {
                throw new IOException("Access token expired and no refresh token is available. Please re-authenticate.");
            }
            SimpleSync.LOGGER.info("[SimpleSync] Access token is expired or close to expiring. Refreshing...");
            
            ClientSecrets secrets = loadClientSecrets();
            if (secrets == null) {
                throw new IOException("client_secret.json is missing. Cannot refresh token.");
            }
            ClientSecrets.Details details = secrets.getDetails();
            
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("client_id", details.client_id);
            if (details.client_secret != null && !details.client_secret.isEmpty()) {
                params.put("client_secret", details.client_secret);
            }
            params.put("refresh_token", tokenData.refreshToken);
            params.put("grant_type", "refresh_token");
            String refreshBody = RetryUtil.formEncode(params);
            
            try {
                HttpResponse<String> response = RetryUtil.postFormWithRetry(httpClient, "https://oauth2.googleapis.com/token", refreshBody);
                if (response.statusCode() == 200) {
                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                    String newAccessToken = body.get("access_token").getAsString();
                    String newRefreshToken = body.has("refresh_token") ? body.get("refresh_token").getAsString() : tokenData.refreshToken;
                    long expiresInSeconds = body.has("expires_in") ? body.get("expires_in").getAsLong() : 3600L;
                    long newExpiresAtMs = System.currentTimeMillis() + (expiresInSeconds * 1000L);
                    
                    tokenData = new TokenStore.TokenData(newAccessToken, newRefreshToken, newExpiresAtMs);
                    TokenStore.save(tokenData);
                    SimpleSync.LOGGER.info("[SimpleSync] Access token successfully refreshed.");
                } else {
                    throw new IOException("Google token refresh returned status " + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                SimpleSync.LOGGER.error("[SimpleSync] Failed to refresh access token", e);
                throw new IOException("Failed to refresh access token: " + e.getMessage(), e);
            }
        }
        
        return tokenData.accessToken;
    }

    private synchronized void ensureSimpleSyncFolder() throws IOException {
        if (simpleSyncFolderId != null) {
            return;
        }

        SyncConfig config = SyncConfig.load();
        String savedFolderId = config.simpleSyncFolderId;
        String accessToken = ensureValidAccessToken();

        if (savedFolderId != null && !savedFolderId.isEmpty()) {
            if (isSafeDriveFileId(savedFolderId)) {
                try {
                    HttpRequest.Builder checkReqBuilder = authedRequest(accessToken, "https://www.googleapis.com/drive/v3/files/" + savedFolderId + "?fields=id,trashed", Duration.ofSeconds(15))
                            .GET();
                    HttpResponse<String> checkResp = sendWithRetry(checkReqBuilder, 3);
                    if (checkResp.statusCode() == 200) {
                        JsonObject folder = JsonParser.parseString(checkResp.body()).getAsJsonObject();
                        if (folder != null && (!folder.has("trashed") || !folder.get("trashed").getAsBoolean())) {
                            simpleSyncFolderId = savedFolderId;
                            return;
                        }
                    } else if (checkResp.statusCode() == 404) {
                        SimpleSync.LOGGER.warn("[SimpleSync] Saved SimpleSync folder ID {} was not found (404). Will recreate...", savedFolderId);
                        config.simpleSyncFolderId = null;
                        config.save();
                    } else {
                        throw new IOException("Failed to verify saved folder ID: HTTP " + checkResp.statusCode());
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
                SimpleSync.LOGGER.warn("[SimpleSync] Ignoring unsafe saved Google Drive folder id");
                config.simpleSyncFolderId = null;
                config.save();
            }
        }

        String query = buildQuery("name='%s' and mimeType='application/vnd.google-apps.folder' and trashed=false", SIMPLESYNC_FOLDER_NAME);
        String searchUrl = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query) + "&fields=files(id)&orderBy=createdTime";
        
        HttpRequest.Builder searchReqBuilder = authedRequest(accessToken, searchUrl, Duration.ofSeconds(30))
                .GET();
        
        try {
            HttpResponse<String> searchResp = sendWithRetry(searchReqBuilder, 3);
            if (searchResp.statusCode() == 200) {
                JsonObject body = JsonParser.parseString(searchResp.body()).getAsJsonObject();
                JsonArray files = body.getAsJsonArray("files");
                if (files != null && files.size() > 0) {
                    simpleSyncFolderId = files.get(0).getAsJsonObject().get("id").getAsString();
                    SimpleSync.LOGGER.info("[SimpleSync] Found SimpleSync folder: {}", simpleSyncFolderId);
                    config.simpleSyncFolderId = simpleSyncFolderId;
                    config.save();
                    return;
                }
            }
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Failed to search for SimpleSync folder: {}", e.getMessage());
        }

        JsonObject folderMeta = new JsonObject();
        folderMeta.addProperty("name", SIMPLESYNC_FOLDER_NAME);
        folderMeta.addProperty("mimeType", "application/vnd.google-apps.folder");

        HttpRequest.Builder createReqBuilder = authedRequest(accessToken, "https://www.googleapis.com/drive/v3/files?fields=id", Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(folderMeta.toString()))
                .header("Content-Type", "application/json; charset=UTF-8");

        try {
            HttpResponse<String> createResp = sendWithRetry(createReqBuilder, 3);
            if (createResp.statusCode() == 200 || createResp.statusCode() == 201) {
                JsonObject folder = JsonParser.parseString(createResp.body()).getAsJsonObject();
                simpleSyncFolderId = folder.get("id").getAsString();
                SimpleSync.LOGGER.info("[SimpleSync] Created SimpleSync folder: {}", simpleSyncFolderId);
                config.simpleSyncFolderId = simpleSyncFolderId;
                config.save();
            } else {
                throw new IOException("Folder creation failed: HTTP " + createResp.statusCode() + " - " + createResp.body());
            }
        } catch (Exception e) {
            throw new IOException("Failed to create SimpleSync folder: " + e.getMessage(), e);
        }
    }

    private synchronized String findFileId(String fileName) throws IOException {
        if (fileIdCache.containsKey(fileName)) {
            return fileIdCache.get(fileName).orElse(null);
        }

        ensureSimpleSyncFolder();
        String worldsFolderId = getWorldsFolderId();
        String accessToken = ensureValidAccessToken();

        String query = buildQuery("name='%s' and ('%s' in parents or '%s' in parents) and trashed=false", fileName, worldsFolderId, simpleSyncFolderId);
        String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query) + "&fields=files(id)&orderBy=modifiedTime%20desc";

        HttpRequest.Builder requestBuilder = authedRequest(accessToken, url, Duration.ofSeconds(30))
                .GET();

        HttpResponse<String> response = sendWithRetry(requestBuilder, 3);
        if (response.statusCode() == 200) {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray files = body.getAsJsonArray("files");
            if (files != null && files.size() > 0) {
                String id = files.get(0).getAsJsonObject().get("id").getAsString();
                fileIdCache.put(fileName, java.util.Optional.of(id));
                return id;
            } else {
                fileIdCache.put(fileName, java.util.Optional.empty());
                return null;
            }
        } else {
            throw new IOException("Failed to search Google Drive for file '" + fileName + "': HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    private void validateWorldName(String worldName) throws IOException {
        if (!WorldSyncTask.isWorldNameSafe(worldName)) {
            throw new IOException("Invalid world name: " + worldName);
        }
    }

    private static HttpRequest.Builder authedRequest(String accessToken, String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(timeout);
    }

    private boolean isSafeDriveFileId(String fileId) {
        return fileId != null && DRIVE_FILE_ID_PATTERN.matcher(fileId).matches();
    }

    private String escapeQueryString(String str) {
        return str != null ? str.replace("\\", "\\\\").replace("'", "\\'") : "";
    }

    private <T> HttpResponse<T> sendWithRetry(HttpRequest.Builder requestBuilder, HttpResponse.BodyHandler<T> responseBodyHandler, int maxAttempts) throws IOException {
        return RetryUtil.retry(maxAttempts, "HTTP Request", () -> {
            boolean refreshedToken = false;
            for (int attempt = 0; attempt < 2; attempt++) {
                HttpRequest request = requestBuilder.build();
                HttpResponse<T> response = httpClient.send(request, responseBodyHandler);
                int statusCode = response.statusCode();
                if (statusCode == 401 && !refreshedToken) {
                    refreshedToken = true;
                    SimpleSync.LOGGER.info("[SimpleSync] HTTP 401 Unauthorized received. Refreshing access token and retrying request...");
                    String newAccessToken = ensureValidAccessToken();
                    requestBuilder.setHeader("Authorization", "Bearer " + newAccessToken);
                    continue;
                }
                if (statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429 && statusCode != 404) {
                    if (response.body() instanceof String strBody) {
                        SimpleSync.LOGGER.error("[SimpleSync] Non-retriable HTTP error ({}): {}", statusCode, strBody);
                    } else {
                        SimpleSync.LOGGER.error("[SimpleSync] Non-retriable HTTP error ({})", statusCode);
                    }
                    return response;
                } else if ((statusCode >= 200 && statusCode < 300) || statusCode == 404) {
                    return response;
                }
                if (response.body() instanceof String strBody) {
                    throw new IOException("Server returned status " + statusCode + ": " + strBody);
                } else {
                    throw new IOException("Server returned status " + statusCode);
                }
            }
            throw new IOException("HTTP 401 Unauthorized after token refresh");
        });
    }

    private HttpResponse<String> sendWithRetry(HttpRequest.Builder requestBuilder, int maxAttempts) throws IOException {
        return sendWithRetry(requestBuilder, HttpResponse.BodyHandlers.ofString(), maxAttempts);
    }

    // ─── Progress Tracking Stream ────────────────────────────────────────────

    private static class ProgressInputStream extends FilterInputStream {
        private final long totalBytes;
        private final String worldName;
        private final boolean upload;
        private long bytesRead = 0;
        private int lastPercent = -1;

        protected ProgressInputStream(InputStream in, long totalBytes, String worldName, boolean upload) {
            this(in, totalBytes, worldName, upload, 0);
        }

        protected ProgressInputStream(InputStream in, long totalBytes, String worldName, boolean upload, long initialOffset) {
            super(in);
            this.totalBytes = totalBytes;
            this.worldName = worldName;
            this.upload = upload;
            this.bytesRead = initialOffset;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                updateProgress(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read != -1) {
                updateProgress(read);
            }
            return read;
        }

        private void updateProgress(long read) {
            bytesRead += read;
            if (totalBytes > 0) {
                int percent = (int) ((bytesRead * 100) / totalBytes);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    SyncStatus status = upload ? SyncStatus.UPLOADING : SyncStatus.DOWNLOADING;
                    CloudSyncManager.getInstance().setStatus(status, worldName + " (" + percent + "%)");
                }
            }
        }
    }
}
