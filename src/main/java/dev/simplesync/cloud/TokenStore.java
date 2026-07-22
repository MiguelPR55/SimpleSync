package dev.simplesync.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class TokenStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TOKENS_FILE = "tokens.json";
    private static final Object FILE_LOCK = new Object();

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
        }
    }

    private static void trySetPermissions(Path path, String posixPermissions) {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(posixPermissions));
        } catch (UnsupportedOperationException | IOException ignored) {}
    }

    public static TokenData load() {
        synchronized (FILE_LOCK) {
            Path tokensFile = getCredentialsDir().resolve(TOKENS_FILE);
            if (!Files.exists(tokensFile) || !Files.isRegularFile(tokensFile)) {
                return null;
            }
            try {
                String json = Files.readString(tokensFile);
                return GSON.fromJson(json, TokenData.class);
            } catch (Exception e) {
                SimpleSync.LOGGER.error("[SimpleSync] Failed to load stored tokens", e);
                return null;
            }
        }
    }

    public static void clear() throws IOException {
        synchronized (FILE_LOCK) {
            Path tokensFile = getCredentialsDir().resolve(TOKENS_FILE);
            Files.deleteIfExists(tokensFile);
        }
    }
}
