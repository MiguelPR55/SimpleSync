package dev.simplesync.cloud;

import dev.simplesync.util.RetryUtil;
import dev.simplesync.util.SyncLogger;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
                .header("User-Agent", "SimpleSync/1.0")
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
                    SyncLogger.info("[SimpleSync] HTTP 401. Refreshing token...");
                    String newToken = tokenManager.forceRefreshToken();
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
        int estimatedSize = (fileBytes != null ? fileBytes.length : 0) + (jsonMeta != null ? jsonMeta.length() : 0) + 256;
        var baos = new ByteArrayOutputStream(estimatedSize);
        try {
            baos.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + jsonMeta + "\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write(("--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            if (fileBytes != null) {
                baos.write(fileBytes);
            }
            baos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
            SyncLogger.error("[SimpleSync] Non-retriable HTTP error ({}): {}", code, s);
        } else {
            SyncLogger.error("[SimpleSync] Non-retriable HTTP error ({})", code);
        }
    }

    private static void closeBodyQuietly(Object body) {
        if (body instanceof InputStream is) {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    // ─── Resumable Upload PUT ─────────────────────────────────────────────

    public HttpResponse<String> uploadResumableFile(String sessionUrl, Path file, String label, long fileSize, boolean trackProgress)
            throws IOException {
        long offset = 0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (offset > 0 || attempt > 1) {
                    HttpRequest statusReq = HttpRequest.newBuilder(URI.create(sessionUrl))
                            .PUT(HttpRequest.BodyPublishers.noBody())
                            .header("Content-Range", "bytes */" + fileSize)
                            .timeout(Duration.ofSeconds(30)).build();
                    HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
                    if (statusResp.statusCode() == 308) {
                        String range = statusResp.headers().firstValue("Range").orElse("");
                        if (range.startsWith("bytes=0-")) {
                            try { offset = Long.parseLong(range.substring(8)) + 1; } catch (NumberFormatException ignored) {}
                        }
                    } else if (statusResp.statusCode() == 200 || statusResp.statusCode() == 201) {
                        return statusResp;
                    }
                }

                final long curOffset = offset;
                final long remaining = fileSize - curOffset;
                final AtomicReference<InputStream> openStream = new AtomicReference<>();
                Supplier<InputStream> supplier = () -> {
                    InputStream prev = openStream.get();
                    if (prev != null) {
                        try { prev.close(); } catch (IOException ignored) {}
                    }
                    try {
                        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
                        if (curOffset > 0) {
                            channel.position(curOffset);
                        }
                        InputStream fis = Channels.newInputStream(channel);
                        InputStream progressStream = trackProgress
                                ? new ProgressInputStream(fis, fileSize, label, true, curOffset)
                                : fis;
                        InputStream bufferedStream = new BufferedInputStream(progressStream, 131_072);
                        openStream.set(bufferedStream);
                        return bufferedStream;
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                };

                var delegate = HttpRequest.BodyPublishers.ofInputStream(supplier);
                HttpRequest.BodyPublisher bodyPub = new HttpRequest.BodyPublisher() {
                    @Override public long contentLength() { return remaining; }
                    @Override public void subscribe(Subscriber<? super ByteBuffer> s) { delegate.subscribe(s); }
                };

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(sessionUrl))
                        .PUT(bodyPub).timeout(Duration.ofMinutes(15));
                if (fileSize > 0) {
                    reqBuilder.header("Content-Range", "bytes " + curOffset + "-" + (fileSize - 1) + "/" + fileSize);
                }

                HttpResponse<String> resp;
                try {
                    resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                } finally {
                    InputStream s = openStream.get();
                    if (s != null) {
                        try { s.close(); } catch (IOException ignored) {}
                    }
                }
                if (resp.statusCode() == 200 || resp.statusCode() == 201) return resp;
                if (resp.statusCode() == 308 && attempt < 3) continue;
                if (resp.statusCode() >= 500 && attempt < 3) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Upload PUT interrupted during sleep", ie);
                    }
                    continue;
                }
                return resp;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Upload PUT interrupted", e);
            } catch (IOException e) {
                if (attempt == 3) throw e;
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Upload PUT interrupted during sleep", ie);
                }
            }
        }
        throw new IOException("Upload PUT failed after retries");
    }
}
