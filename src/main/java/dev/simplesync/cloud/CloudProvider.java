package dev.simplesync.cloud;

import dev.simplesync.sync.WorldMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Interface for cloud storage providers.
 * Implementations handle authentication, upload, download, and listing of world backups.
 */
public interface CloudProvider {

    /**
     * @return The display name of this provider (e.g., "Google Drive")
     */
    String getName();

    /**
     * @return true if the provider has valid stored credentials
     */
    boolean isAuthenticated();

    /**
     * Initiates the authentication flow.
     * @throws IOException if authentication fails
     */
    void authenticate() throws IOException;

    /**
     * Uploads a world ZIP file to the cloud.
     * @param worldName The name of the world
     * @param zipFile   Path to the ZIP file to upload
     * @throws IOException if upload fails
     */
    void upload(String worldName, Path zipFile) throws IOException;

    /**
     * Downloads a world ZIP file from the cloud.
     * @param worldName The name of the world to download
     * @param outputZip Path where the downloaded ZIP should be saved
     * @throws IOException if download fails
     */
    void download(String worldName, Path outputZip) throws IOException;

    /**
     * Lists all worlds available in the cloud.
     * @return List of world metadata
     * @throws IOException if listing fails
     */
    List<WorldMetadata> listWorlds() throws IOException;

    /**
     * Gets metadata for a specific world in the cloud.
     * @param worldName The name of the world
     * @return Metadata for the world, or null if not found
     * @throws IOException if the request fails
     */
    WorldMetadata getWorldMetadata(String worldName) throws IOException;

    /**
     * Deletes a world from the cloud.
     * @param worldName The name of the world to delete
     * @throws IOException if deletion fails
     */
    void delete(String worldName) throws IOException;
}
