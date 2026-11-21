package dev.simplesync.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.util.SyncLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

public class TokenStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TOKENS_FILE = "tokens.json";
    private static final Object FILE_LOCK = new Object();
    private static volatile TokenData cachedTokenData;
    private static volatile long cachedTokenMtime = -1L;

    public static class TokenData {
        public String accessToken;
        public String refreshToken;
        public long expiresAtMs;

        public TokenData(String accessToken, String refreshToken, long expiresAtMs) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static Path getCredentialsDir() {
        return SyncConfig.getConfigDir().resolve("credentials");
    }

    public static void save(TokenData data) throws IOException {
        synchronized (FILE_LOCK) {
            Path credentialsDirectory = getCredentialsDir();
            Files.createDirectories(credentialsDirectory);
            trySetPermissions(credentialsDirectory, "rwx------");
            Path tokensFile = credentialsDirectory.resolve(TOKENS_FILE);
            Path tempFile = credentialsDirectory.resolve(TOKENS_FILE + ".tmp");

            String json = GSON.toJson(data);
            Files.writeString(tempFile, json);
            trySetPermissions(tempFile, "rw-------");
            try {
                Files.move(tempFile, tokensFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tempFile, tokensFile, StandardCopyOption.REPLACE_EXISTING);
            }
            trySetPermissions(tokensFile, "rw-------");
            cachedTokenData = data;
            try {
                cachedTokenMtime = Files.getLastModifiedTime(tokensFile).toMillis();
            } catch (IOException ignored) {
                cachedTokenMtime = System.currentTimeMillis();
            }
        }
    }

    private static void trySetPermissions(Path path, String posixPermissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixPermissions));
        } catch (UnsupportedOperationException | IOException ignored) {}
    }

    public static TokenData load() {
        synchronized (FILE_LOCK) {
            Path tokensFile = getCredentialsDir().resolve(TOKENS_FILE);
            if (!Files.exists(tokensFile) || !Files.isRegularFile(tokensFile)) {
                cachedTokenData = null;
                cachedTokenMtime = -1L;
                return null;
            }
            try {
                long mtime = Files.getLastModifiedTime(tokensFile).toMillis();
                if (cachedTokenData != null && mtime == cachedTokenMtime) {
                    return cachedTokenData;
                }
                String json = Files.readString(tokensFile);
                TokenData parsed = GSON.fromJson(json, TokenData.class);
                cachedTokenData = parsed;
                cachedTokenMtime = mtime;
                return parsed;
            } catch (Exception e) {
                SyncLogger.error("[SimpleSync] Failed to load stored tokens", e);
                return null;
            }
        }
    }

    public static void invalidateCache() {
        synchronized (FILE_LOCK) {
            cachedTokenData = null;
            cachedTokenMtime = -1L;
        }
    }

    public static void clear() throws IOException {
        synchronized (FILE_LOCK) {
            cachedTokenData = null;
            cachedTokenMtime = -1L;
            Path tokensFile = getCredentialsDir().resolve(TOKENS_FILE);
            Files.deleteIfExists(tokensFile);
        }
    }
}
