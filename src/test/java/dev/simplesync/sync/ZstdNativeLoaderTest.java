package dev.simplesync.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ZstdNativeLoaderTest {

    @Test
    void testDetectPlatform_ReturnsNonNullOnStandardPlatforms() {
        String platform = ZstdNativeLoader.detectPlatform();
        assertNotNull(platform, "Platform should be successfully detected");
        assertTrue(platform.startsWith("win_") || platform.startsWith("linux_") || platform.startsWith("darwin_"),
                "Platform must be one of win, linux, or darwin");
    }

    @Test
    void testGetNativeCachePath_ValidFormat() {
        String platform = ZstdNativeLoader.detectPlatform();
        if (platform != null) {
            Path path = ZstdNativeLoader.getNativeCachePath(platform);
            assertNotNull(path);
            String pathStr = path.toString().replace('\\', '/');
            assertTrue(pathStr.contains("natives/" + platform));
            assertTrue(pathStr.endsWith(".dll") || pathStr.endsWith(".so") || pathStr.endsWith(".dylib"));
        }
    }

    @Test
    void testEnsureLoaded_Succeeds() {
        boolean loaded = ZstdNativeLoader.ensureLoaded();
        assertTrue(loaded, "Zstd native should be loaded successfully");
        assertTrue(com.github.luben.zstd.util.Native.isLoaded(), "Native.isLoaded() should be true");
    }
}
