package dev.simplesync.cloud;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.SyncStatus;
import dev.simplesync.sync.WorldMetadata;
import dev.simplesync.sync.WorldSyncTask;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Google Drive implementation of CloudProvider.
 * Uses OAuth2 for authentication and stores worlds as ZIP files in a dedicated folder.
 */
public class GoogleDriveProvider implements CloudProvider {

    private static final String APPLICATION_NAME = "SimpleSync Minecraft Mod";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE);
    private static final String SIMPLESYNC_FOLDER_NAME = "SimpleSync";
    private static final String ZIP_MIME_TYPE = "application/zip";
    private static final Pattern DRIVE_FILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");

    private final Path credentialsDir;
    private final Map<String, String> fileIdCache = new ConcurrentHashMap<>();
    private Drive driveService;
    private String simpleSyncFolderId;

    public GoogleDriveProvider() {
        this.credentialsDir = SyncConfig.getConfigDir().resolve("credentials");
    }

    @Override
    public String getName() {
        return "Google Drive";
    }

    @Override
    public boolean isAuthenticated() {
        try {
            if (driveService != null) {
                // Verify the credential is still usable
                return verifyDriveServiceCredential();
            }
            return initializeDriveServiceOffline();
        } catch (Exception e) {
            SimpleSync.LOGGER.error("[SimpleSync] Error checking authentication status", e);
            return false;
        }
    }

    /**
     * Verifies that the current driveService has a valid (or refreshable) credential.
     */
    private boolean verifyDriveServiceCredential() {
        try {
            Credential cred = driveService.getRequestFactory().getInitializer() instanceof Credential c ? c : null;
            if (cred == null) {
                return true; // Cannot inspect, assume valid
            }
            Long expiresIn = cred.getExpiresInSeconds();
            if (expiresIn != null && expiresIn <= 60) {
                if (cred.getRefreshToken() != null) {
                    return cred.refreshToken();
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            SimpleSync.LOGGER.warn("[SimpleSync] Failed to verify credential: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void authenticate() throws IOException {
        try {
            initializeDriveService();
            if (driveService == null) {
                throw new IOException("Failed to initialize Google Drive service");
            }
            ensureSimpleSyncFolder();
            SimpleSync.LOGGER.info("[SimpleSync] Successfully authenticated with Google Drive");
        } catch (GeneralSecurityException e) {
            throw new IOException("Security error during authentication", e);
        }
    }

    @Override
    public WorldMetadata upload(String worldName, Path zipFile) throws IOException {
        validateWorldName(worldName);
        ensureAuthenticated();
        ensureSimpleSyncFolder();

        String fileName = worldName + ".zip";
        SimpleSync.LOGGER.info("[SimpleSync] Uploading {} to Google Drive...", fileName);

        String existingFileId = findFileId(fileName);

        File fileMetadata = new File();
        fileMetadata.setName(fileName);

        FileContent mediaContent = new FileContent(ZIP_MIME_TYPE, zipFile.toFile());

        File uploadedFile;
        if (existingFileId != null) {
            uploadedFile = withRetry(() -> {
                Drive.Files.Update request = driveService.files().update(existingFileId, fileMetadata, mediaContent)
                        .setFields("id, name, modifiedTime, size");
                request.getMediaHttpUploader().setProgressListener(uploader -> {
                    switch (uploader.getUploadState()) {
                        case MEDIA_IN_PROGRESS -> {
                            int percent = (int) (uploader.getProgress() * 100);
                            CloudSyncManager.getInstance().setStatus(SyncStatus.UPLOADING, worldName + " (" + percent + "%)");
                        }
                        case MEDIA_COMPLETE -> CloudSyncManager.getInstance().setStatus(SyncStatus.UPLOADING, worldName + " (100%)");
                        default -> {}
                    }
                });
                return request.execute();
            }, 3);
            SimpleSync.LOGGER.info("[SimpleSync] Updated existing file: {} (ID: {})", fileName, uploadedFile.getId());
        } else {
            fileMetadata.setParents(Collections.singletonList(simpleSyncFolderId));
            uploadedFile = withRetry(() -> {
                Drive.Files.Create request = driveService.files().create(fileMetadata, mediaContent)
                        .setFields("id, name, modifiedTime, size");
                request.getMediaHttpUploader().setProgressListener(uploader -> {
                    switch (uploader.getUploadState()) {
                        case MEDIA_IN_PROGRESS -> {
                            int percent = (int) (uploader.getProgress() * 100);
                            CloudSyncManager.getInstance().setStatus(SyncStatus.UPLOADING, worldName + " (" + percent + "%)");
                        }
                        case MEDIA_COMPLETE -> CloudSyncManager.getInstance().setStatus(SyncStatus.UPLOADING, worldName + " (100%)");
                        default -> {}
                    }
                });
                return request.execute();
            }, 3);
            SimpleSync.LOGGER.info("[SimpleSync] Uploaded new file: {} (ID: {})", fileName, uploadedFile.getId());
        }
        if (uploadedFile != null && uploadedFile.getId() != null) {
            fileIdCache.put(fileName, uploadedFile.getId());
        }
        long mtime = (uploadedFile != null && uploadedFile.getModifiedTime() != null) ? uploadedFile.getModifiedTime().getValue() : System.currentTimeMillis();
        long size = (uploadedFile != null && uploadedFile.getSize() != null) ? uploadedFile.getSize() : 0;
        String id = (uploadedFile != null) ? uploadedFile.getId() : existingFileId;
        return new WorldMetadata(worldName, mtime, size, id);
    }

    @Override
    public void download(String worldName, Path outputZip) throws IOException {
        validateWorldName(worldName);
        ensureAuthenticated();

        String fileName = worldName + ".zip";
        String fileId = findFileId(fileName);

        if (fileId == null) {
            throw new IOException("World not found in cloud: " + worldName);
        }

        SimpleSync.LOGGER.info("[SimpleSync] Downloading {} from Google Drive...", fileName);

        Files.createDirectories(outputZip.getParent());
        if (Files.isSymbolicLink(outputZip)) {
            throw new IOException("Refusing to write download through symbolic link: " + outputZip);
        }

        withRetry(() -> {
            Files.deleteIfExists(outputZip);
            try (OutputStream outputStream = Files.newOutputStream(outputZip, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                Drive.Files.Get getRequest = driveService.files().get(fileId);
                getRequest.getMediaHttpDownloader().setProgressListener(downloader -> {
                    switch (downloader.getDownloadState()) {
                        case MEDIA_IN_PROGRESS -> {
                            int percent = (int) (downloader.getProgress() * 100);
                            CloudSyncManager.getInstance().setStatus(SyncStatus.DOWNLOADING, worldName + " (" + percent + "%)");
                        }
                        case MEDIA_COMPLETE -> CloudSyncManager.getInstance().setStatus(SyncStatus.DOWNLOADING, worldName + " (100%)");
                        case NOT_STARTED -> {}
                    }
                });
                getRequest.executeMediaAndDownloadTo(outputStream);
            }
            return null;
        }, 3);

        SimpleSync.LOGGER.info("[SimpleSync] Download complete: {}", outputZip);
    }

    @Override
    public List<WorldMetadata> listWorlds() throws IOException {
        ensureAuthenticated();
        ensureSimpleSyncFolder();

        List<WorldMetadata> worlds = new ArrayList<>();
        fileIdCache.clear();

        String query = String.format("'%s' in parents and mimeType='%s' and trashed=false",
                simpleSyncFolderId, ZIP_MIME_TYPE);

        String pageToken = null;
        do {
            final String currentPageToken = pageToken;
            FileList result = withRetry(() -> driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, modifiedTime, size)")
                    .setPageSize(1000)
                    .setPageToken(currentPageToken)
                    .setOrderBy("modifiedTime desc")
                    .execute(), 3);

            if (result == null || result.getFiles() == null) {
                break;
            }

            for (File file : result.getFiles()) {
                String fileName = file.getName();
                if (fileName == null || !fileName.endsWith(".zip")) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Ignoring Google Drive file with unexpected name: {}", fileName);
                    continue;
                }
                String worldName = fileName.substring(0, fileName.length() - 4);
                if (!WorldSyncTask.isWorldNameSafe(worldName)) {
                    SimpleSync.LOGGER.warn("[SimpleSync] Ignoring Google Drive file with unsafe world name: {}", fileName);
                    continue;
                }
                if (file.getId() != null) {
                    fileIdCache.put(fileName, file.getId());
                }

                long modifiedTime = (file.getModifiedTime() != null) ? file.getModifiedTime().getValue() : System.currentTimeMillis();

                worlds.add(new WorldMetadata(
                        worldName,
                        modifiedTime,
                        file.getSize() != null ? file.getSize() : 0,
                        file.getId()
                ));
            }

            pageToken = result.getNextPageToken();
        } while (pageToken != null);

        return worlds;
    }

    @Override
    public WorldMetadata getWorldMetadata(String worldName) throws IOException {
        validateWorldName(worldName);
        ensureAuthenticated();

        String fileName = worldName + ".zip";
        String fileId = findFileId(fileName);

        if (fileId == null) {
            return null;
        }

        File file = withRetry(() -> driveService.files().get(fileId)
                .setFields("id, name, modifiedTime, size")
                .execute(), 3);

        return new WorldMetadata(
                worldName,
                file.getModifiedTime().getValue(),
                file.getSize() != null ? file.getSize() : 0,
                file.getId()
        );
    }

    @Override
    public void delete(String worldName) throws IOException {
        validateWorldName(worldName);
        ensureAuthenticated();

        String fileName = worldName + ".zip";
        String fileId = findFileId(fileName);

        if (fileId != null) {
            withRetry(() -> {
                driveService.files().delete(fileId).execute();
                return null;
            }, 3);
            fileIdCache.remove(fileName);
            SimpleSync.LOGGER.info("[SimpleSync] Deleted {} from Google Drive", fileName);
        }
    }

    @Override
    public void disconnect() throws IOException {
        driveService = null;
        simpleSyncFolderId = null;
        fileIdCache.clear();
        if (Files.isSymbolicLink(credentialsDir)) {
            throw new IOException("Refusing to clear symlinked credentials directory: " + credentialsDir);
        }
        if (Files.isDirectory(credentialsDir)) {
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(credentialsDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry)) {
                        Files.delete(entry);
                    }
                }
            }
        }
        SimpleSync.LOGGER.info("[SimpleSync] Disconnected from Google Drive and cleared stored credentials");
    }

    // ─── Private Helpers ────────────────────────────────────────────────────

    private GoogleClientSecrets loadClientSecrets() throws IOException {
        Path clientSecretFile = SyncConfig.getConfigDir().resolve("client_secret.json");
        if (!Files.exists(clientSecretFile)) {
            SimpleSync.LOGGER.warn("[SimpleSync] No client_secret.json found at: {}", clientSecretFile);
            SimpleSync.LOGGER.warn("[SimpleSync] Please place your Google OAuth2 client_secret.json in the config/simplesync/ folder.");
            SimpleSync.LOGGER.warn("[SimpleSync] Instructions: https://console.cloud.google.com/apis/credentials");
            return null;
        }
        try (InputStream in = Files.newInputStream(clientSecretFile)) {
            GoogleClientSecrets secrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in, StandardCharsets.UTF_8));
            if (secrets.getInstalled() == null && secrets.getWeb() == null) {
                throw new IOException("Invalid Google OAuth client secrets file: missing installed/web section");
            }
            return secrets;
        }
    }

    private GoogleAuthorizationCodeFlow createFlow(NetHttpTransport transport, GoogleClientSecrets secrets) throws IOException {
        if (secrets == null) {
            return null;
        }
        if (Files.isSymbolicLink(credentialsDir)) {
            throw new IOException("Refusing to use symlinked credentials directory: " + credentialsDir);
        }
        Files.createDirectories(credentialsDir);
        return new GoogleAuthorizationCodeFlow.Builder(transport, JSON_FACTORY, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(credentialsDir.toFile()))
                .setAccessType("offline")
                .build();
    }

    private void initializeDriveService() throws IOException, GeneralSecurityException {
        final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleClientSecrets secrets = loadClientSecrets();
        if (secrets == null) {
            throw new IOException("client_secret.json is missing! Please place your Google OAuth2 client_secret.json in the config/simplesync/ folder.");
        }

        GoogleAuthorizationCodeFlow flow = createFlow(transport, secrets);
        if (flow == null) {
            return;
        }

        GoogleClientSecrets.Details details = secrets.getInstalled() != null ? secrets.getInstalled() : secrets.getWeb();
        if (details == null) {
            throw new IOException("Invalid Google OAuth client secrets file: missing installed/web section");
        }

        Credential credential;
        try {
            credential = new DeviceCodeAuthenticator().authenticate(
                    flow,
                    details.getClientId(),
                    details.getClientSecret(),
                    SCOPES.get(0),
                    CloudSyncManager.getInstance().getAuthPromptCallback()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Authentication flow was interrupted", e);
        }

        driveService = new Drive.Builder(transport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private boolean initializeDriveServiceOffline() {
        try {
            final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            GoogleAuthorizationCodeFlow flow = createFlow(transport, loadClientSecrets());
            if (flow == null) {
                return false;
            }

            Credential cred = flow.loadCredential("user");
            if (cred != null && (cred.getRefreshToken() != null || cred.getAccessToken() != null)) {
                Long expiresIn = cred.getExpiresInSeconds();
                if (expiresIn != null && expiresIn <= 60) {
                    if (cred.getRefreshToken() == null) {
                        SimpleSync.LOGGER.warn("[SimpleSync] Access token has expired and no refresh token is available.");
                        return false;
                    }
                    try {
                        if (!cred.refreshToken()) {
                            SimpleSync.LOGGER.warn("[SimpleSync] Failed to refresh expired access token.");
                            return false;
                        }
                    } catch (Exception e) {
                        SimpleSync.LOGGER.warn("[SimpleSync] Exception refreshing access token offline: {}", e.getMessage());
                        return false;
                    }
                }
                driveService = new Drive.Builder(transport, JSON_FACTORY, cred)
                        .setApplicationName(APPLICATION_NAME)
                        .build();
                return true;
            }
        } catch (Exception e) {
            SimpleSync.LOGGER.error("[SimpleSync] Exception while checking stored offline credentials", e);
        }
        return false;
    }

    private void ensureAuthenticated() throws IOException {
        if (driveService == null) {
            try {
                initializeDriveService();
            } catch (GeneralSecurityException e) {
                throw new IOException("Failed to authenticate with Google Drive", e);
            }
        }
        if (driveService == null) {
            throw new IOException("Google Drive is not authenticated. Please set up client_secret.json.");
        }
    }

    private synchronized void ensureSimpleSyncFolder() throws IOException {
        if (simpleSyncFolderId != null) {
            return;
        }

        SyncConfig config = SyncConfig.load();
        String savedFolderId = config.getSimpleSyncFolderId();
        if (savedFolderId != null && !savedFolderId.isEmpty()) {
            if (isSafeDriveFileId(savedFolderId)) {
                simpleSyncFolderId = savedFolderId;
                return;
            }
            SimpleSync.LOGGER.warn("[SimpleSync] Ignoring unsafe saved Google Drive folder id");
            config.setSimpleSyncFolderId(null);
            config.save();
        }

        FileList result = withRetry(() -> driveService.files().list()
                .setQ("name='" + escapeQueryString(SIMPLESYNC_FOLDER_NAME) + "' and mimeType='application/vnd.google-apps.folder' and trashed=false")
                .setFields("files(id)")
                .setOrderBy("createdTime")
                .execute(), 3);

        if (result != null && result.getFiles() != null && !result.getFiles().isEmpty()) {
            simpleSyncFolderId = result.getFiles().get(0).getId();
            if (!isSafeDriveFileId(simpleSyncFolderId)) {
                throw new IOException("Google Drive returned an invalid SimpleSync folder id");
            }
            SimpleSync.LOGGER.info("[SimpleSync] Found SimpleSync folder: {}", simpleSyncFolderId);
        } else {
            File folderMetadata = new File();
            folderMetadata.setName(SIMPLESYNC_FOLDER_NAME);
            folderMetadata.setMimeType("application/vnd.google-apps.folder");

            File folder = withRetry(() -> driveService.files().create(folderMetadata)
                    .setFields("id")
                    .execute(), 3);

            simpleSyncFolderId = folder.getId();
            if (!isSafeDriveFileId(simpleSyncFolderId)) {
                throw new IOException("Google Drive returned an invalid SimpleSync folder id");
            }
            SimpleSync.LOGGER.info("[SimpleSync] Created SimpleSync folder: {}", simpleSyncFolderId);
        }

        config.setSimpleSyncFolderId(simpleSyncFolderId);
        config.save();
    }

    private synchronized String findFileId(String fileName) throws IOException {
        if (fileIdCache.containsKey(fileName)) {
            return fileIdCache.get(fileName);
        }

        ensureSimpleSyncFolder();

        String query = String.format("name='%s' and '%s' in parents and trashed=false",
                escapeQueryString(fileName), simpleSyncFolderId);

        FileList result = withRetry(() -> driveService.files().list()
                .setQ(query)
                .setFields("files(id)")
                .setOrderBy("modifiedTime desc")
                .execute(), 3);

        if (result != null && result.getFiles() != null && !result.getFiles().isEmpty()) {
            String id = result.getFiles().get(0).getId();
            fileIdCache.put(fileName, id);
            return id;
        }
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

    private <T> T withRetry(Callable<T> action, int maxAttempts) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (IOException e) {
                if (e instanceof GoogleJsonResponseException jsonEx) {
                    int statusCode = jsonEx.getStatusCode();
                    if (statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429) {
                        SimpleSync.LOGGER.error("[SimpleSync] Non-retriable HTTP error ({}): {}", statusCode, e.getMessage());
                        throw e;
                    }
                }
                lastError = e;
                SimpleSync.LOGGER.warn("[SimpleSync] Attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(1000L * (1L << (attempt - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw lastError;
                    }
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        throw lastError != null ? lastError : new IOException("Operation failed after retries");
    }
}
