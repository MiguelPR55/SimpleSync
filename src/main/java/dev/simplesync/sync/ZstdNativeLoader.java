package dev.simplesync.sync;

import com.github.luben.zstd.util.Native;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.util.SyncLogger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ensures Zstandard native library (zstd-jni) is loaded.
 * If bundled natives are not in the JAR (to keep mod size ~300KB),
 * it dynamically downloads and caches the platform-specific native binary.
 */
public final class ZstdNativeLoader {

    private static final String ZSTD_VERSION = "1.5.6-8";
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    private ZstdNativeLoader() {}

    public static synchronized boolean ensureLoaded() {
        if (LOADED.get() || Native.isLoaded()) {
            LOADED.set(true);
            return true;
        }

        // Try standard load first (in case natives are on classpath or system)
        try {
            Native.load();
            if (Native.isLoaded()) {
                LOADED.set(true);
                return true;
            }
        } catch (Throwable ignored) {}

        String platform = detectPlatform();
        if (platform == null) {
            SyncLogger.warn("[SimpleSync] Unsupported OS/Arch for Zstandard native loader.");
            return false;
        }

        Path nativeFile = getNativeCachePath(platform);
        if (Files.isRegularFile(nativeFile)) {
            try {
                System.load(nativeFile.toAbsolutePath().toString());
                Native.assumeLoaded();
                LOADED.set(true);
                SyncLogger.info("[SimpleSync] Loaded cached Zstandard native from: {}", nativeFile.getFileName());
                return true;
            } catch (Throwable t) {
                SyncLogger.warn("[SimpleSync] Failed to load cached native, re-downloading...", t);
                try { Files.deleteIfExists(nativeFile); } catch (IOException ignored) {}
            }
        }

        // Download platform jar and extract native library
        boolean success = downloadAndLoadNative(platform, nativeFile);
        if (success) {
            LOADED.set(true);
        }
        return success;
    }

    private static boolean downloadAndLoadNative(String platform, Path targetPath) {
        String jarName = "zstd-jni-" + ZSTD_VERSION + "-" + platform + ".jar";
        String url = "https://repo1.maven.org/maven2/com/github/luben/zstd-jni/" + ZSTD_VERSION + "/" + jarName;

        SyncLogger.info("[SimpleSync] Downloading Zstandard native library for {}...", platform);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
                SyncLogger.error("[SimpleSync] Failed to download Zstandard native: HTTP {}", response.statusCode());
                return false;
            }

            byte[] nativeBytes = null;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!entry.isDirectory() && (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib"))) {
                        nativeBytes = zis.readAllBytes();
                        break;
                    }
                }
            }

            if (nativeBytes == null) {
                SyncLogger.error("[SimpleSync] Could not find native library file inside downloaded {}", jarName);
                return false;
            }

            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }

            Path tempFile = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
            Files.write(tempFile, nativeBytes);
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            System.load(targetPath.toAbsolutePath().toString());
            Native.assumeLoaded();
            SyncLogger.info("[SimpleSync] Successfully downloaded and loaded Zstandard native for {}!", platform);
            return true;
        } catch (Throwable t) {
            SyncLogger.error("[SimpleSync] Failed to download/load Zstandard native", t);
            return false;
        }
    }

    public static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        boolean isAmd64 = arch.equals("x86_64") || arch.equals("amd64");
        boolean isArm64 = arch.equals("aarch64") || arch.equals("arm64");

        if (os.contains("win")) {
            if (isAmd64) return "win_amd64";
            if (isArm64) return "win_aarch64";
            if (arch.equals("x86") || arch.equals("i386")) return "win_x86";
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (isArm64) return "darwin_aarch64";
            if (isAmd64) return "darwin_x86_64";
        } else if (os.contains("linux") || os.contains("unix")) {
            if (isAmd64) return "linux_amd64";
            if (isArm64) return "linux_aarch64";
        }
        return null;
    }

    public static Path getNativeCachePath(String platform) {
        String ext = platform.startsWith("win") ? ".dll" : (platform.startsWith("darwin") ? ".dylib" : ".so");
        return SyncConfig.getConfigDir().resolve("natives").resolve(platform).resolve("libzstd-jni-" + ZSTD_VERSION + ext);
    }
}
