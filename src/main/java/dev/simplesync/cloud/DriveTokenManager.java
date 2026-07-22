package dev.simplesync.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplesync.SimpleSync;
import dev.simplesync.config.SyncConfig;
import dev.simplesync.util.RetryUtil;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages OAuth2 token lifecycle: loading client secrets, refreshing access tokens.
 */
public class DriveTokenManager {

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private volatile ClientSecrets cachedSecrets;
    private volatile long lastSecretsMtime = -1;

    public DriveTokenManager(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ─── Client Secrets ───────────────────────────────────────────────────

    public static class ClientSecrets {
        public Details installed;
        public Details web;

        public static class Details {
            public String client_id;
            public String client_secret;
        }

        public Details getDetails() {
            return installed != null ? installed : web;
        }
    }

    public synchronized ClientSecrets loadClientSecrets() throws IOException {
        Path clientSecretFile = SyncConfig.getConfigDir().resolve("client_secret.json");
        if (!Files.exists(clientSecretFile)) {
            SimpleSync.LOGGER.warn("[SimpleSync] No client_secret.json found at: {}", clientSecretFile);
            cachedSecrets = null;
            lastSecretsMtime = -1;
            return null;
        }
        try {
            long currentMtime = Files.getLastModifiedTime(clientSecretFile).toMillis();
            if (cachedSecrets != null && currentMtime == lastSecretsMtime) {
                return cachedSecrets;
            }
            String json = Files.readString(clientSecretFile);
            ClientSecrets secrets = GSON.fromJson(json, ClientSecrets.class);
            if (secrets == null || secrets.getDetails() == null) {
                throw new IOException("Invalid client secrets: missing installed/web section");
            }
            cachedSecrets = secrets;
            lastSecretsMtime = currentMtime;
            return secrets;
        } catch (Exception e) {
            throw new IOException("Failed to load client_secret.json: " + e.getMessage(), e);
        }
    }

    // ─── Access Token ─────────────────────────────────────────────────────

    public synchronized String ensureValidAccessToken() throws IOException {
        TokenStore.TokenData tokenData = TokenStore.load();
        if (tokenData == null) {
            throw new IOException("Google Drive is not authenticated. Please authenticate.");
        }

        if (System.currentTimeMillis() >= tokenData.expiresAtMs - 300_000) {
            if (tokenData.refreshToken == null || tokenData.refreshToken.isEmpty()) {
                throw new IOException("Access token expired and no refresh token available. Re-authenticate.");
            }
            SimpleSync.LOGGER.info("[SimpleSync] Access token expiring. Refreshing...");
            tokenData = refreshToken(tokenData);
        }

        return tokenData.accessToken;
    }

    private TokenStore.TokenData refreshToken(TokenStore.TokenData old) throws IOException {
        ClientSecrets secrets = loadClientSecrets();
        if (secrets == null) {
            throw new IOException("client_secret.json is missing. Cannot refresh token.");
        }
        ClientSecrets.Details details = secrets.getDetails();

        Map<String, String> params = new HashMap<>();
        params.put("client_id", details.client_id);
        if (details.client_secret != null && !details.client_secret.isEmpty()) {
            params.put("client_secret", details.client_secret);
        }
        params.put("refresh_token", old.refreshToken);
        params.put("grant_type", "refresh_token");

        try {
            HttpResponse<String> response = RetryUtil.postFormWithRetry(
                    httpClient, "https://oauth2.googleapis.com/token", RetryUtil.formEncode(params));

            if (response.statusCode() != 200) {
                throw new IOException("Token refresh returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            String newAccess = body.get("access_token").getAsString();
            String newRefresh = body.has("refresh_token") ? body.get("refresh_token").getAsString() : old.refreshToken;
            long expiresInSeconds = body.has("expires_in") ? body.get("expires_in").getAsLong() : 3600L;
            long newExpiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L);

            TokenStore.TokenData newToken = new TokenStore.TokenData(newAccess, newRefresh, newExpiresAt);
            TokenStore.save(newToken);
            SimpleSync.LOGGER.info("[SimpleSync] Access token refreshed.");
            return newToken;
        } catch (Exception e) {
            throw new IOException("Failed to refresh access token: " + e.getMessage(), e);
        }
    }
}
