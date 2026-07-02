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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
            if (driveService == null) {
                initializeDriveService();
            }
            return driveService != null;
        } catch (Exception e) {
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
    public void upload(String worldName, Path zipFile) throws IOException {
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
            uploadedFile = driveService.files().update(existingFileId, fileMetadata, mediaContent)
                    .setFields("id, name, modifiedTime, size")
                    .execute();
            SimpleSync.LOGGER.info("[SimpleSync] Updated existing file: {} (ID: {})", fileName, uploadedFile.getId());
        } else {
            fileMetadata.setParents(Collections.singletonList(simpleSyncFolderId));
            uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, modifiedTime, size")
                    .execute();
            SimpleSync.LOGGER.info("[SimpleSync] Uploaded new file: {} (ID: {})", fileName, uploadedFile.getId());
        }
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

        try (OutputStream outputStream = Files.newOutputStream(outputZip)) {
            driveService.files().get(fileId)
                    .executeMediaAndDownloadTo(outputStream);
        }

        SimpleSync.LOGGER.info("[SimpleSync] Download complete: {}", outputZip);
    }

    @Override
    public List<WorldMetadata> listWorlds() throws IOException {
        ensureAuthenticated();
        ensureSimpleSyncFolder();

        List<WorldMetadata> worlds = new ArrayList<>();

        String query = String.format("'%s' in parents and mimeType='%s' and trashed=false",
                simpleSyncFolderId, ZIP_MIME_TYPE);

        FileList result = driveService.files().list()
                .setQ(query)
                .setFields("files(id, name, modifiedTime, size)")
                .setOrderBy("modifiedTime desc")
                .execute();

        for (File file : result.getFiles()) {
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

        File file = driveService.files().get(fileId)
                .setFields("id, name, modifiedTime, size")
                .execute();

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
            driveService.files().delete(fileId).execute();
            SimpleSync.LOGGER.info("[SimpleSync] Deleted {} from Google Drive", fileName);
        }
    }

    // ─── Private Helpers ────────────────────────────────────────────────────

    private void initializeDriveService() throws IOException, GeneralSecurityException {
        Path clientSecretFile = SyncConfig.getConfigDir().resolve("client_secret.json");

        if (!Files.exists(clientSecretFile)) {
            SimpleSync.LOGGER.warn("[SimpleSync] No client_secret.json found at: {}", clientSecretFile);
            SimpleSync.LOGGER.warn("[SimpleSync] Please place your Google OAuth2 client_secret.json in the config/simplesync/ folder.");
            SimpleSync.LOGGER.warn("[SimpleSync] Instructions: https://console.cloud.google.com/apis/credentials");
            return;
        }

        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        GoogleClientSecrets clientSecrets;
        try (InputStream in = Files.newInputStream(clientSecretFile)) {
            clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
        }

        Files.createDirectories(credentialsDir);
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(credentialsDir.toFile()))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(8888)
                .build();

        Credential credential = new com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp(
                flow, receiver).authorize("user");

        driveService = new Drive.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
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

        FileList result = driveService.files().list()
                .setQ("name='" + SIMPLESYNC_FOLDER_NAME + "' and mimeType='application/vnd.google-apps.folder' and trashed=false")
                .setFields("files(id)")
                .execute();

        if (!result.getFiles().isEmpty()) {
            simpleSyncFolderId = result.getFiles().get(0).getId();
            SimpleSync.LOGGER.info("[SimpleSync] Found SimpleSync folder: {}", simpleSyncFolderId);
        } else {
            File folderMetadata = new File();
            folderMetadata.setName(SIMPLESYNC_FOLDER_NAME);
            folderMetadata.setMimeType("application/vnd.google-apps.folder");

            File folder = driveService.files().create(folderMetadata)
                    .setFields("id")
                    .execute();

            simpleSyncFolderId = folder.getId();
            SimpleSync.LOGGER.info("[SimpleSync] Created SimpleSync folder: {}", simpleSyncFolderId);
        }
    }

    private String findFileId(String fileName) throws IOException {
        ensureSimpleSyncFolder();

        String query = String.format("name='%s' and '%s' in parents and trashed=false",
                fileName, simpleSyncFolderId);

        FileList result = driveService.files().list()
                .setQ(query)
                .setFields("files(id)")
                .setOrderBy("modifiedTime desc")
                .execute();

        if (!result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        }
        return null;
    }
}
