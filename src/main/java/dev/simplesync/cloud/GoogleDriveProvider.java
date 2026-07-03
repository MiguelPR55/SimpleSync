package dev.simplesync.cloud;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
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
import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.sync.WorldMetadata;

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
                return true;
            }
            return initializeDriveServiceOffline();
        } catch (Exception e) {
            SimpleSync.LOGGER.error("[SimpleSync] Error checking authentication status", e);
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
            uploadedFile = withRetry(() -> driveService.files().update(existingFileId, fileMetadata, mediaContent)
                    .setFields("id, name, modifiedTime, size")
                    .execute(), 3);
            SimpleSync.LOGGER.info("[SimpleSync] Updated existing file: {} (ID: {})", fileName, uploadedFile.getId());
        } else {
            fileMetadata.setParents(Collections.singletonList(simpleSyncFolderId));
            uploadedFile = withRetry(() -> driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, modifiedTime, size")
                    .execute(), 3);
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
        ensureAuthenticated();

        String fileName = worldName + ".zip";
        String fileId = findFileId(fileName);

        if (fileId == null) {
            throw new IOException("World not found in cloud: " + worldName);
        }

        SimpleSync.LOGGER.info("[SimpleSync] Downloading {} from Google Drive...", fileName);

        Files.createDirectories(outputZip.getParent());

        withRetry(() -> {
            try (OutputStream outputStream = Files.newOutputStream(outputZip)) {
                driveService.files().get(fileId)
                        .executeMediaAndDownloadTo(outputStream);
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

        FileList result = withRetry(() -> driveService.files().list()
                .setQ(query)
                .setFields("files(id, name, modifiedTime, size)")
                .setOrderBy("modifiedTime desc")
                .execute(), 3);

        if (result == null || result.getFiles() == null) {
            return worlds;
        }

        for (File file : result.getFiles()) {
            if (file.getName() != null && file.getId() != null) {
                fileIdCache.put(file.getName(), file.getId());
            }
            String worldName = file.getName();
            if (worldName.endsWith(".zip")) {
                worldName = worldName.substring(0, worldName.length() - 4);
            }

            worlds.add(new WorldMetadata(
                    worldName,
                    file.getModifiedTime().getValue(),
                    file.getSize() != null ? file.getSize() : 0,
                    file.getId()
            ));
        }

        return worlds;
    }

    @Override
    public WorldMetadata getWorldMetadata(String worldName) throws IOException {
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

    // ─── Private Helpers ────────────────────────────────────────────────────

    private GoogleAuthorizationCodeFlow createFlow(NetHttpTransport transport) throws IOException, GeneralSecurityException {
        Path clientSecretFile = SyncConfig.getConfigDir().resolve("client_secret.json");
        if (!Files.exists(clientSecretFile)) return null;
        GoogleClientSecrets secrets;
        try (InputStream in = Files.newInputStream(clientSecretFile)) {
            secrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Files.createDirectories(credentialsDir);
        return new GoogleAuthorizationCodeFlow.Builder(transport, JSON_FACTORY, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(credentialsDir.toFile())).setAccessType("offline").build();
    }

    private void initializeDriveService() throws IOException, GeneralSecurityException {
        final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleAuthorizationCodeFlow flow = createFlow(transport);
        if (flow == null) {
            SimpleSync.LOGGER.warn("[SimpleSync] No client_secret.json found in config/simplesync/ folder.");
            return;
        }
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(-1).build();
        Credential credential = new com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        driveService = new Drive.Builder(transport, JSON_FACTORY, credential).setApplicationName(APPLICATION_NAME).build();
    }

    private boolean initializeDriveServiceOffline() {
        try {
            final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            GoogleAuthorizationCodeFlow flow = createFlow(transport);
            if (flow == null) return false;
            Credential cred = flow.loadCredential("user");
            if (cred != null && (cred.getRefreshToken() != null || cred.getAccessToken() != null)) {
                driveService = new Drive.Builder(transport, JSON_FACTORY, cred).setApplicationName(APPLICATION_NAME).build();
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

    private void ensureSimpleSyncFolder() throws IOException {
        if (simpleSyncFolderId != null) {
            return;
        }

        SyncConfig config = SyncConfig.load();
        String savedFolderId = config.getSimpleSyncFolderId();
        if (savedFolderId != null && !savedFolderId.isEmpty()) {
            simpleSyncFolderId = savedFolderId;
            return;
        }

        FileList result = withRetry(() -> driveService.files().list()
                .setQ("name='" + escapeQueryString(SIMPLESYNC_FOLDER_NAME) + "' and mimeType='application/vnd.google-apps.folder' and trashed=false")
                .setFields("files(id)")
                .setOrderBy("createdTime")
                .execute(), 3);

        if (result != null && result.getFiles() != null && !result.getFiles().isEmpty()) {
            simpleSyncFolderId = result.getFiles().get(0).getId();
            SimpleSync.LOGGER.info("[SimpleSync] Found SimpleSync folder: {}", simpleSyncFolderId);
        } else {
            File folderMetadata = new File();
            folderMetadata.setName(SIMPLESYNC_FOLDER_NAME);
            folderMetadata.setMimeType("application/vnd.google-apps.folder");

            File folder = withRetry(() -> driveService.files().create(folderMetadata)
                    .setFields("id")
                    .execute(), 3);

            simpleSyncFolderId = folder.getId();
            SimpleSync.LOGGER.info("[SimpleSync] Created SimpleSync folder: {}", simpleSyncFolderId);
        }

        config.setSimpleSyncFolderId(simpleSyncFolderId);
        config.save();
    }

    private String findFileId(String fileName) throws IOException {
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

    private String escapeQueryString(String str) {
        return str != null ? str.replace("'", "\\'") : "";
    }

    private <T> T withRetry(Callable<T> action, int maxAttempts) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (IOException e) {
                lastError = e;
                SimpleSync.LOGGER.warn("[SimpleSync] Attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                try { Thread.sleep(1000L * (1L << (attempt - 1))); } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        throw lastError != null ? lastError : new IOException("Operation failed after retries");
    }
}
