package dev.simplesync.util;

import dev.simplesync.SimpleSync;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class RetryUtil {

    public static String formEncode(Map<String, String> params) {
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
                if (e.getMessage() != null && (e.getMessage().contains("invalid_grant") || e.getMessage().contains("invalid_token") || e.getMessage().contains("permanently revoked"))) {
                    SimpleSync.LOGGER.error("[SimpleSync] Fatal authentication error (not retrying): {}", e.getMessage());
                    throw e;
                }
                SimpleSync.LOGGER.warn("[SimpleSync] {} attempt {}/{} failed: {}", operationName, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        long baseDelay = 1000L * (1L << (attempt - 1));
                        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(-baseDelay / 5, baseDelay / 5 + 1);
                        Thread.sleep(Math.max(100L, baseDelay + jitter));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException(operationName + " interrupted during retry delay", ie);
                    }
                }
            } catch (Exception e) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new IOException(e);
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
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    public interface RunnableWithException {
        void run() throws Exception;
    }
}
