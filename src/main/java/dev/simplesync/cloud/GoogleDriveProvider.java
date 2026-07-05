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

    private static final String SIMPLESYNC_FOLDER_NAME = "SimpleSync";
    private static final String ZIP_MIME_TYPE = "application/zip";
    private static final String TAR_ZST_MIME_TYPE = "application/zstd";
    private static final Pattern DRIVE_FILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");

    private final Path credentialsDir;
    private final Map<String, java.util.Optional<String>> fileIdCache = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private volatile String simpleSyncFolderId;
    private final AtomicBoolean isAuthenticating = new AtomicBoolean(false);

    private record ArchiveFileIds(String tarZstId, String zipId) {}

    public GoogleDriveProvider() {
        this.credentialsDir = SyncConfig.getConfigDir().resolve("credentials");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
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
            JsonArray parents = new JsonArray();
            parents.add(simpleSyncFolderId);
            metadata.add("parents", parents);
        }

        String method = isUpdate ? "PATCH" : "POST";
        HttpRequest.Builder initReqBuilder = HttpRequest.newBuilder(URI.create(initUrl))
                .method(method, HttpRequest.BodyPublishers.ofString(metadata.toString()))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", String.valueOf(fileSize))
                .timeout(Duration.ofSeconds(30));

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
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/" + targetFileId + "?alt=media"))
                .GET()
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofMinutes(15));

        try {
            HttpResponse<InputStream> response = sendWithRetry(reqBuilder, HttpResponse.BodyHandlers.ofInputStream(), 3);
            if (response.statusCode() != 200) {
                throw new IOException("Download failed: HTTP " + response.statusCode());
            }
            try (InputStream is = new ProgressInputStream(response.body(), fileSize, worldName, false);
                 OutputStream os = Files.newOutputStream(outputArchive, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
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

        String query = buildQuery("'%s' in parents and trashed=false", simpleSyncFolderId);
        java.util.Set<String> seenWorldNames = new java.util.HashSet<>();
        String pageToken = null;
        String accessToken = ensureValidAccessToken();

        do {
            String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query)
                    + "&fields=nextPageToken,files(id,name,modifiedTime,size)&pageSize=1000"
                    + "&orderBy=modifiedTime%20desc"
                    + (pageToken != null ? "&pageToken=" + RetryUtil.urlEncode(pageToken) : "");

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(30));

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
            WorldMetadata metaTarZst = getFileMetadataById(ids.tarZstId(), worldName);
            WorldMetadata metaZip = getFileMetadataById(ids.zipId(), worldName);
            
            if (metaZip != null && metaTarZst != null) {
                if (metaZip.lastModified() > metaTarZst.lastModified()) {
                    fileIdCache.put(worldName + ".zip", java.util.Optional.ofNullable(ids.zipId()));
                    fileIdCache.remove(worldName + ".tar.zst");
                    return metaZip;
                } else {
                    fileIdCache.put(worldName + ".tar.zst", java.util.Optional.ofNullable(ids.tarZstId()));
                    fileIdCache.remove(worldName + ".zip");
                    return metaTarZst;
                }
            } else if (metaZip != null) {
                return metaZip;
            } else {
                return metaTarZst;
            }
        } else {
            String targetFileId = (ids.tarZstId() != null) ? ids.tarZstId() : ids.zipId();
            return getFileMetadataById(targetFileId, worldName);
        }
    }

    private WorldMetadata getFileMetadataById(String fileId, String worldName) throws IOException {
        String accessToken = ensureValidAccessToken();
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/" + fileId + "?fields=id,name,modifiedTime,size"))
                .GET()
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(30));
        
        HttpResponse<String> response = sendWithRetry(reqBuilder, 3);
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
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/" + fileId))
                .DELETE()
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(30));
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

    private ClientSecrets loadClientSecrets() throws IOException {
        Path clientSecretFile = SyncConfig.getConfigDir().resolve("client_secret.json");
        if (!Files.exists(clientSecretFile)) {
            SimpleSync.LOGGER.warn("[SimpleSync] No client_secret.json found at: {}", clientSecretFile);
            SimpleSync.LOGGER.warn("[SimpleSync] Please place your Google OAuth2 client_secret.json in the config/simplesync/ folder.");
            SimpleSync.LOGGER.warn("[SimpleSync] Instructions: https://console.cloud.google.com/apis/credentials");
            return null;
        }
        try {
            String json = Files.readString(clientSecretFile);
            ClientSecrets secrets = new Gson().fromJson(json, ClientSecrets.class);
            if (secrets == null || secrets.getDetails() == null) {
                throw new IOException("Invalid Google OAuth client secrets file: missing installed/web section");
            }
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
            
            String refreshBody = "client_id=" + RetryUtil.urlEncode(details.client_id)
                    + (details.client_secret != null && !details.client_secret.isEmpty() ? "&client_secret=" + RetryUtil.urlEncode(details.client_secret) : "")
                    + "&refresh_token=" + RetryUtil.urlEncode(tokenData.refreshToken)
                    + "&grant_type=refresh_token";
            
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
                    HttpRequest.Builder checkReqBuilder = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/" + savedFolderId + "?fields=id,trashed"))
                            .GET()
                            .header("Authorization", "Bearer " + accessToken)
                            .timeout(Duration.ofSeconds(15));
                    HttpResponse<String> checkResp = sendWithRetry(checkReqBuilder, 3);
                    if (checkResp.statusCode() == 200) {
                        JsonObject folder = JsonParser.parseString(checkResp.body()).getAsJsonObject();
                        if (folder != null && (!folder.has("trashed") || !folder.get("trashed").getAsBoolean())) {
                            simpleSyncFolderId = savedFolderId;
                            return;
                        }
                    } else if (checkResp.statusCode() == 404) {
                        SimpleSync.LOGGER.warn("[SimpleSync] Saved SimpleSync folder ID {} was not found (404).", savedFolderId);
                    }
                } catch (Exception e) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Failed to verify saved SimpleSync folder ID {} ({}). Will search or recreate...", savedFolderId, e.getMessage());
                }
            } else {
                SimpleSync.LOGGER.warn("[SimpleSync] Ignoring unsafe saved Google Drive folder id");
            }
            config.simpleSyncFolderId = null;
            config.save();
        }

        String query = buildQuery("name='%s' and mimeType='application/vnd.google-apps.folder' and trashed=false", SIMPLESYNC_FOLDER_NAME);
        String searchUrl = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query) + "&fields=files(id)&orderBy=createdTime";
        
        HttpRequest.Builder searchReqBuilder = HttpRequest.newBuilder(URI.create(searchUrl))
                .GET()
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(30));
        
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

        HttpRequest.Builder createReqBuilder = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files?fields=id"))
                .POST(HttpRequest.BodyPublishers.ofString(folderMeta.toString()))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .timeout(Duration.ofSeconds(30));

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
        String accessToken = ensureValidAccessToken();

        String query = buildQuery("name='%s' and '%s' in parents and trashed=false", fileName, simpleSyncFolderId);
        String url = "https://www.googleapis.com/drive/v3/files?q=" + RetryUtil.urlEncode(query) + "&fields=files(id)&orderBy=modifiedTime%20desc";

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(30));

        try {
            HttpResponse<String> response = sendWithRetry(reqBuilder, 3);
            if (response.statusCode() == 200) {
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray files = body.getAsJsonArray("files");
                if (files != null && files.size() > 0) {
                    String id = files.get(0).getAsJsonObject().get("id").getAsString();
                    fileIdCache.put(fileName, java.util.Optional.of(id));
                    return id;
                }
            }
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Failed to find file ID for {}: {}", fileName, e.getMessage());
        }
        fileIdCache.put(fileName, java.util.Optional.empty());
        return null;
    }

    private void validateWorldName(String worldName) throws IOException {
        if (!WorldSyncTask.isWorldNameSafe(worldName)) {
            throw new IOException("Invalid world name: " + worldName);
        }
    }

    private boolean isSafeDriveFileId(String fileId) {
        return fileId != null && DRIVE_FILE_ID_PATTERN.matcher(fileId).matches();
    }

    private String escapeQueryString(String str) {
        return str != null ? str.replace("\\", "\\\\").replace("'", "\\'") : "";
    }

    private <T> HttpResponse<T> sendWithRetry(HttpRequest.Builder requestBuilder, HttpResponse.BodyHandler<T> responseBodyHandler, int maxAttempts) throws IOException {
        return RetryUtil.retry(maxAttempts, "HTTP Request", () -> {
            HttpRequest request = requestBuilder.build();
            HttpResponse<T> response = httpClient.send(request, responseBodyHandler);
            int statusCode = response.statusCode();
            if (statusCode == 401) {
                String newAccessToken = ensureValidAccessToken();
                requestBuilder.setHeader("Authorization", "Bearer " + newAccessToken);
                throw new IOException("HTTP 401 Unauthorized (refreshed access token, retrying...)");
            } else if (statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429 && statusCode != 404) {
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
