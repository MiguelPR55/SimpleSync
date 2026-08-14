package dev.simplesync.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Utility for exponential backoff retries, URL parameter encoding, and form posts.
 */
public final class RetryUtil {

    private RetryUtil() {}

    public static String formEncode(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        return params.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    public static <T> T retry(int maxAttempts, String operationName, Callable<T> action) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(operationName + " interrupted", e);
            } catch (IOException e) {
                lastError = e;
                if (isFatalAuthError(e.getMessage())) {
                    SyncLogger.error("[SimpleSync] Fatal authentication error (not retrying): {}", e.getMessage());
                    throw e;
                }
                SyncLogger.warn("[SimpleSync] {} attempt {}/{} failed: {}", operationName, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleepWithBackoff(attempt, operationName);
                }
            } catch (Exception e) {
                if (e instanceof UncheckedIOException uioe) {
                    lastError = uioe.getCause();
                    SyncLogger.warn("[SimpleSync] {} attempt {}/{} failed (UncheckedIOException): {}", operationName, attempt, maxAttempts, lastError.getMessage());
                    if (attempt < maxAttempts) {
                        sleepWithBackoff(attempt, operationName);
                    }
                } else if (e instanceof RuntimeException re) {
                    throw re;
                } else {
                    throw new IOException(e);
                }
            }
        }
        throw lastError != null ? lastError : new IOException(operationName + " failed after retries");
    }

    public static void retryVoid(int maxAttempts, String operationName, RunnableWithException action) throws IOException {
        retry(maxAttempts, operationName, () -> {
            action.run();
            return null;
        });
    }

    public static HttpResponse<String> postFormWithRetry(HttpClient client, String url, String body) throws IOException {
        return retry(3, "OAuth POST", () -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 500) throw new IOException("Server returned HTTP " + resp.statusCode());
            return resp;
        });
    }

    public static String urlEncode(String s) {
        return s != null ? URLEncoder.encode(s, StandardCharsets.UTF_8) : "";
    }

    private static boolean isFatalAuthError(String msg) {
        return msg != null && (msg.contains("invalid_grant") || msg.contains("invalid_token") || msg.contains("permanently revoked"));
    }

    private static void sleepWithBackoff(int attempt, String operationName) throws IOException {
        try {
            long baseDelay = 1000L * (1L << (attempt - 1));
            long jitter = ThreadLocalRandom.current().nextLong(-baseDelay / 5, baseDelay / 5 + 1);
            Thread.sleep(Math.max(100L, baseDelay + jitter));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException(operationName + " interrupted during retry delay", ie);
        }
    }

    @FunctionalInterface
    public interface RunnableWithException {
        void run() throws Exception;
    }
}
