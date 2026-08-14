package dev.simplesync.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CloudProviderFactoryTest {

    @Test
    void testCreateDefaultGoogleDriveProvider() {
        CloudProvider provider = CloudProviderFactory.create("google_drive");
        assertNotNull(provider);
        assertEquals("Google Drive", provider.getName());
    }

    @Test
    void testCreateFallbackOnNullOrEmpty() {
        CloudProvider providerNull = CloudProviderFactory.create(null);
        assertNotNull(providerNull);
        assertEquals("Google Drive", providerNull.getName());

        CloudProvider providerEmpty = CloudProviderFactory.create("");
        assertNotNull(providerEmpty);
        assertEquals("Google Drive", providerEmpty.getName());
    }

    @Test
    void testRegisterCustomProvider() {
        CloudProvider mockProvider = new CloudProvider() {
            @Override public String getName() { return "Mock Cloud"; }
            @Override public boolean isAuthenticated() { return true; }
            @Override public void authenticate() {}
            @Override public boolean isAuthenticating() { return false; }
            @Override public dev.simplesync.sync.WorldMetadata upload(String worldName, java.nio.file.Path file) { return null; }
            @Override public void download(String worldName, java.nio.file.Path file) {}
            @Override public java.util.List<dev.simplesync.sync.WorldMetadata> listWorlds() { return java.util.List.of(); }
            @Override public dev.simplesync.sync.WorldMetadata getWorldMetadata(String worldName) { return null; }
            @Override public void delete(String worldName) {}
            @Override public void disconnect() {}
            @Override public void syncSchematics(java.nio.file.Path gameRootDir) {}
            @Override public void syncMasaConfigs(java.nio.file.Path gameRootDir) {}
        };

        CloudProviderFactory.register("mock_cloud", () -> mockProvider);
        CloudProvider created = CloudProviderFactory.create("mock_cloud");

        assertNotNull(created);
        assertEquals("Mock Cloud", created.getName());
        assertTrue(created.isAuthenticated());
    }
}
