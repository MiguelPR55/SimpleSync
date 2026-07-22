package dev.simplesync.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplesync.SimpleSync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements the Google OAuth 2.0 Device Authorization Grant (RFC 8628) using Java's native HttpClient.
 * Stores retrieved tokens into TokenStore.
 */
public class DeviceCodeAuthenticator {

    private static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * Executes the device authorization grant flow.
     * Must be called from a background worker thread (never on the render thread).
     *
     * @param clientId     The OAuth Client ID
     * @param clientSecret The OAuth Client Secret
     * @param scope        The requested scope (e.g., "https://www.googleapis.com/auth/drive.file")
     * @param callback     Optional callback to display the user code and verification URL in the UI
     * @throws IOException          if authentication fails or is cancelled
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void authenticate(
            String clientId,
            String clientSecret,
            String scope,
            AuthPromptCallback callback
    ) throws IOException, InterruptedException {
        SimpleSync.LOGGER.info("[SimpleSync] Initiating Google Device Authorization Grant...");

        String deviceCodeBody = dev.simplesync.util.RetryUtil.formEncode(java.util.Map.of(
                "client_id", clientId,
                "scope", scope
        ));
        JsonObject deviceResponse = post(DEVICE_CODE_URL, deviceCodeBody);
        
        if (deviceResponse == null || !deviceResponse.has("device_code") || !deviceResponse.has("user_code") || !deviceResponse.has("expires_in")) {
            throw new IOException("Malformed Google OAuth Device Authorization response: " + (deviceResponse != null ? deviceResponse.toString() : "null"));
        }

        String deviceCode = deviceResponse.get("device_code").getAsString();
        String userCode = deviceResponse.get("user_code").getAsString();
        String verificationUrl = deviceResponse.has("verification_url")
                ? deviceResponse.get("verification_url").getAsString()
                : (deviceResponse.has("verification_uri") ? deviceResponse.get("verification_uri").getAsString() : "https://www.google.com/device");
        long expiresIn = deviceResponse.get("expires_in").getAsLong();
        long intervalSeconds = deviceResponse.has("interval") ? deviceResponse.get("interval").getAsLong() : 5;

        SimpleSync.LOGGER.info("[SimpleSync] Device Auth prompt -> URL: {}, Code: {} (Expires in {}s)", verificationUrl, userCode, expiresIn);

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Thread workerThread = Thread.currentThread();

        if (callback != null) {
            callback.onAuthPrompt(userCode, verificationUrl, expiresIn, () -> {
                SimpleSync.LOGGER.info("[SimpleSync] User requested cancellation of Device Auth prompt");
                cancelled.set(true);
                workerThread.interrupt();
            });
        }

        long deadline = System.currentTimeMillis() + expiresIn * 1000L;

        while (System.currentTimeMillis() < deadline) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new AuthCancelledException("Authentication cancelled by user");
            }

            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthCancelledException("Authentication cancelled by user");
            }

            if (cancelled.get()) {
                throw new AuthCancelledException("Authentication cancelled by user");
            }

            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("client_id", clientId);
            if (clientSecret != null && !clientSecret.isEmpty()) {
                params.put("client_secret", clientSecret);
            }
            params.put("device_code", deviceCode);
            params.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            String tokenBody = dev.simplesync.util.RetryUtil.formEncode(params);

            HttpResponse<String> tokenResponse;
            try {
                tokenResponse = dev.simplesync.util.RetryUtil.postFormWithRetry(httpClient, TOKEN_URL, tokenBody);
            } catch (IOException e) {
                SimpleSync.LOGGER.warn("[SimpleSync] Transient network error during authentication polling: {}", e.getMessage());
                continue;
            }
            JsonObject responseBody = JsonParser.parseString(tokenResponse.body()).getAsJsonObject();

            if (tokenResponse.statusCode() == 200 && responseBody.has("access_token")) {
                SimpleSync.LOGGER.info("[SimpleSync] Successfully obtained tokens via Device Authorization Grant!");
                String accessToken = responseBody.get("access_token").getAsString();
                String refreshToken = responseBody.has("refresh_token") ? responseBody.get("refresh_token").getAsString() : null;
                long expiresInSeconds = responseBody.has("expires_in") ? responseBody.get("expires_in").getAsLong() : 3600L;
                long expiresAtMs = System.currentTimeMillis() + (expiresInSeconds * 1000L);
                
                TokenStore.save(new TokenStore.TokenData(accessToken, refreshToken, expiresAtMs));
                return;
            }

            String error = responseBody.has("error") ? responseBody.get("error").getAsString() : "unknown_error";
            switch (error) {
                case "authorization_pending" -> {
                    // Waiting for user to complete authorization in browser
                }
                case "slow_down" -> {
                    intervalSeconds += 5; // RFC 8628 requirement
                }
                case "expired_token" -> throw new IOException("Authorization code has expired. Please try again.");
                case "access_denied" -> throw new IOException("Authentication denied by user.");
                default -> throw new IOException("Authentication failed: " + error + (responseBody.has("error_description") ? " - " + responseBody.get("error_description").getAsString() : ""));
            }
        }

        throw new IOException("Authentication timed out before completion.");
    }

    private JsonObject post(String url, String body) throws IOException, InterruptedException {
        HttpResponse<String> resp = dev.simplesync.util.RetryUtil.postFormWithRetry(httpClient, url, body);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP error from Google API (" + resp.statusCode() + "): " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    public static class AuthCancelledException extends IOException {
        public AuthCancelledException(String message) {
            super(message);
        }
    }
}
