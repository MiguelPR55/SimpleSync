package dev.simplesync.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplesync.SimpleSync;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

        String deviceCodeBody = "client_id=" + enc(clientId) + "&scope=" + enc(scope);
        JsonObject deviceResp = post(DEVICE_CODE_URL, deviceCodeBody);
        
        if (deviceResp == null || !deviceResp.has("device_code") || !deviceResp.has("user_code") || !deviceResp.has("expires_in")) {
            throw new IOException("Malformed Google OAuth Device Authorization response: " + (deviceResp != null ? deviceResp.toString() : "null"));
        }

        String deviceCode = deviceResp.get("device_code").getAsString();
        String userCode = deviceResp.get("user_code").getAsString();
        String verificationUrl = deviceResp.has("verification_url")
                ? deviceResp.get("verification_url").getAsString()
                : (deviceResp.has("verification_uri") ? deviceResp.get("verification_uri").getAsString() : "https://www.google.com/device");
        long expiresIn = deviceResp.get("expires_in").getAsLong();
        long intervalSeconds = deviceResp.has("interval") ? deviceResp.get("interval").getAsLong() : 5;

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
                throw new IOException("Authentication cancelled by user");
            }

            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                if (cancelled.get()) {
                    throw new IOException("Authentication cancelled by user");
                }
                throw e;
            }

            if (cancelled.get()) {
                throw new IOException("Authentication cancelled by user");
            }

            String tokenBody = "client_id=" + enc(clientId)
                    + (clientSecret != null && !clientSecret.isEmpty() ? "&client_secret=" + enc(clientSecret) : "")
                    + "&device_code=" + enc(deviceCode)
                    + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code");

            HttpResponse<String> raw;
            try {
                raw = rawPostWithRetry(TOKEN_URL, tokenBody);
            } catch (IOException e) {
                SimpleSync.LOGGER.warn("[SimpleSync] Transient network error during authentication polling: {}", e.getMessage());
                continue;
            }
            JsonObject body = JsonParser.parseString(raw.body()).getAsJsonObject();

            if (raw.statusCode() == 200 && body.has("access_token")) {
                SimpleSync.LOGGER.info("[SimpleSync] Successfully obtained tokens via Device Authorization Grant!");
                String accessToken = body.get("access_token").getAsString();
                String refreshToken = body.has("refresh_token") ? body.get("refresh_token").getAsString() : null;
                long expiresInSeconds = body.has("expires_in") ? body.get("expires_in").getAsLong() : 3600L;
                long expiresAtMs = System.currentTimeMillis() + (expiresInSeconds * 1000L);
                
                TokenStore.save(new TokenStore.TokenData(accessToken, refreshToken, expiresAtMs));
                return;
            }

            String error = body.has("error") ? body.get("error").getAsString() : "unknown_error";
            switch (error) {
                case "authorization_pending" -> {
                    // Waiting for user to complete authorization in browser
                }
                case "slow_down" -> {
                    intervalSeconds += 5; // RFC 8628 requirement
                }
                case "expired_token" -> throw new IOException("Authorization code has expired. Please try again.");
                case "access_denied" -> throw new IOException("Authentication denied by user.");
                default -> throw new IOException("Authentication failed: " + error + (body.has("error_description") ? " - " + body.get("error_description").getAsString() : ""));
            }
        }

        throw new IOException("Authentication timed out before completion.");
    }

    private JsonObject post(String url, String body) throws IOException, InterruptedException {
        HttpResponse<String> resp = rawPostWithRetry(url, body);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP error from Google API (" + resp.statusCode() + "): " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private HttpResponse<String> rawPostWithRetry(String url, String body) throws IOException, InterruptedException {
        int attempt = 0;
        int maxAttempts = 3;
        IOException lastEx = null;
        while (attempt < maxAttempts) {
            attempt++;
            try {
                HttpResponse<String> resp = rawPost(url, body);
                if (resp.statusCode() >= 500) {
                    lastEx = new IOException("Google server returned HTTP " + resp.statusCode());
                } else {
                    return resp;
                }
            } catch (IOException e) {
                lastEx = e;
            }
            if (attempt < maxAttempts) {
                long delay = 1000L * (1L << (attempt - 1));
                SimpleSync.LOGGER.warn("[SimpleSync] Transient post error (attempt {}/{}), retrying in {}ms...", attempt, maxAttempts, delay);
                Thread.sleep(delay);
            }
        }
        throw lastEx;
    }

    private HttpResponse<String> rawPost(String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
