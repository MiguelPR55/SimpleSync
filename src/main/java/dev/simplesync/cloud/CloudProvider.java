package dev.simplesync.cloud;

import dev.simplesync.sync.WorldMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Interface for cloud storage providers.
 * Implementations handle authentication, upload, download, and listing of world backups and extra files.
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
     * @return true if an authentication flow is currently in progress
     */
    boolean isAuthenticating();

    /**
     * Uploads a world archive file (tar.zst or zip) to the cloud.
     * @param worldName    The name of the world
     * @param archiveFile  Path to the archive file to upload
     * @return Metadata of the uploaded world (including server authoritative timestamp)
     * @throws IOException if upload fails
     */
    WorldMetadata upload(String worldName, Path archiveFile) throws IOException;

    /**
     * Downloads a world archive file from the cloud.
     * @param worldName     The name of the world to download
     * @param outputArchive Path where the downloaded archive should be saved
     * @throws IOException if download fails
     */
    void download(String worldName, Path outputArchive) throws IOException;

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

    /**
     * Disconnects the provider, clearing cache and deleting stored OAuth credentials.
     * @throws IOException if clearing credentials fails
     */
    void disconnect() throws IOException;

    /**
     * Synchronizes the schematics directory incrementally.
     * @param gameRootDir Path to the game root directory
     * @throws IOException if sync fails
     */
    void syncSchematics(Path gameRootDir) throws IOException;

    /**
     * Synchronizes Masa mod configuration files and directories incrementally.
     * @param gameRootDir Path to the game root directory
     * @throws IOException if sync fails
     */
    void syncMasaConfigs(Path gameRootDir) throws IOException;

    /**
     * Shuts down any background pools or resources associated with this provider.
     */
    default void shutdown() {}
}
