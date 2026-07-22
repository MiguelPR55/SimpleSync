package dev.simplesync.cloud;

import dev.simplesync.SimpleSync;
import dev.simplesync.util.RetryUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Low-level HTTP helper for Google Drive REST API.
 * Handles auth headers, retries, token refresh on 401, and multipart body construction.
 */
public class DriveApiClient {

    private final HttpClient httpClient;
    private final DriveTokenManager tokenManager;

    public DriveApiClient(HttpClient httpClient, DriveTokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    // ─── Request Builders ─────────────────────────────────────────────────

    public HttpRequest.Builder authedRequest(String url, Duration timeout) throws IOException {
        String token = tokenManager.ensureValidAccessToken();
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .timeout(timeout);
    }

    // ─── Send with Retry ──────────────────────────────────────────────────

    public <T> HttpResponse<T> send(HttpRequest.Builder requestBuilder,
                                    HttpResponse.BodyHandler<T> handler,
                                    int maxAttempts) throws IOException {
        return RetryUtil.retry(maxAttempts, "HTTP Request", () -> {
            boolean refreshedToken = false;
            for (int attempt = 0; attempt < 2; attempt++) {
                HttpRequest request = requestBuilder.build();
                HttpResponse<T> response = httpClient.send(request, handler);
                int code = response.statusCode();

                if (code == 401 && !refreshedToken) {
                    refreshedToken = true;
                    closeBodyQuietly(response.body());
                    SimpleSync.LOGGER.info("[SimpleSync] HTTP 401. Refreshing token...");
                    String newToken = tokenManager.ensureValidAccessToken();
                    requestBuilder.setHeader("Authorization", "Bearer " + newToken);
                    continue;
                }

                // Non-retriable client errors (except 408, 429, 404)
                if (code >= 400 && code < 500 && code != 408 && code != 429 && code != 404) {
                    logErrorBody(code, response.body());
                    return response;
                }

                // Success or 404 (handled by caller)
                if ((code >= 200 && code < 300) || code == 404) {
                    return response;
                }

                // Retriable
                closeBodyQuietly(response.body());
                throw new IOException("Server returned status " + code);
            }
            throw new IOException("HTTP 401 after token refresh");
        });
    }

    public HttpResponse<String> send(HttpRequest.Builder requestBuilder, int maxAttempts) throws IOException {
        return send(requestBuilder, HttpResponse.BodyHandlers.ofString(), maxAttempts);
    }

    // ─── Multipart Body ───────────────────────────────────────────────────

    public static byte[] buildMultipartBody(String boundary, String jsonMeta, byte[] fileBytes) {
        var baos = new java.io.ByteArrayOutputStream();
        try {
            baos.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + jsonMeta + "\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write(("--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write(fileBytes);
            baos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    public static String escapeQueryString(String str) {
        return str != null ? str.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"") : "";
    }

    public static String buildQuery(String format, String... args) {
        Object[] escaped = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            escaped[i] = escapeQueryString(args[i]);
        }
        return String.format(format, escaped);
    }

    private void logErrorBody(int code, Object body) {
        if (body instanceof String s) {
            SimpleSync.LOGGER.error("[SimpleSync] Non-retriable HTTP error ({}): {}", code, s);
        } else {
            SimpleSync.LOGGER.error("[SimpleSync] Non-retriable HTTP error ({})", code);
        }
    }

    private static void closeBodyQuietly(Object body) {
        if (body instanceof java.io.InputStream is) {
            try { is.close(); } catch (Exception ignored) {}
        }
    }
}
