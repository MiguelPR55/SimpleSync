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
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
                HttpRequest.BodyPublisher bodyPub = createFilePublisher(file, curOffset, remaining, fileSize, label, trackProgress);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(sessionUrl))
                        .PUT(bodyPub).timeout(Duration.ofMinutes(15));
                if (fileSize > 0) {
                    reqBuilder.header("Content-Range", "bytes " + curOffset + "-" + (fileSize - 1) + "/" + fileSize);
                }

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
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

    private static final int UPLOAD_CHUNK_SIZE = 262_144; // 256 KB chunks

    static HttpRequest.BodyPublisher createFilePublisher(
            Path file, long startOffset, long remaining, long totalFileSize, String label, boolean trackProgress) {
        return new HttpRequest.BodyPublisher() {
            @Override public long contentLength() { return remaining; }

            @Override
            public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
                try {
                    FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
                    if (startOffset > 0) {
                        channel.position(startOffset);
                    }
                    subscriber.onSubscribe(new Flow.Subscription() {
                        private final AtomicBoolean completed = new AtomicBoolean(false);
                        private final AtomicLong requested = new AtomicLong(0);
                        private final Object lock = new Object();
                        private long totalRead = 0;
                        private int lastPercent = -1;

                        @Override
                        public void request(long n) {
                            if (n <= 0) {
                                onError(new IllegalArgumentException("non-positive subscription request"));
                                return;
                            }
                            requested.addAndGet(n);
                            drain();
                        }

                        @Override
                        public void cancel() {
                            closeChannel();
                        }

                        private void drain() {
                            synchronized (lock) {
                                while (requested.get() > 0 && !completed.get()) {
                                    if (totalRead >= remaining) {
                                        onComplete();
                                        return;
                                    }
                                    try {
                                        int toRead = (int) Math.min(UPLOAD_CHUNK_SIZE, remaining - totalRead);
                                        ByteBuffer buf = ByteBuffer.allocate(toRead);
                                        int read = channel.read(buf);
                                        if (read <= 0) {
                                            onComplete();
                                            return;
                                        }
                                        buf.flip();
                                        totalRead += read;
                                        if (trackProgress && totalFileSize > 0) {
                                            long overall = startOffset + totalRead;
                                            int pct = (int) ((overall * 100) / totalFileSize);
                                            if (pct != lastPercent) {
                                                lastPercent = pct;
                                                dev.simplesync.cloud.CloudSyncManager.getInstance()
                                                        .setStatus(dev.simplesync.sync.SyncStatus.UPLOADING, label + " (" + pct + "%)");
                                            }
                                        }
                                        requested.decrementAndGet();
                                        subscriber.onNext(buf);
                                    } catch (Throwable t) {
                                        onError(t);
                                        return;
                                    }
                                }
                            }
                        }

                        private void onComplete() {
                            if (completed.compareAndSet(false, true)) {
                                closeChannel();
                                subscriber.onComplete();
                            }
                        }

                        private void onError(Throwable t) {
                            if (completed.compareAndSet(false, true)) {
                                closeChannel();
                                subscriber.onError(t);
                            }
                        }

                        private void closeChannel() {
                            try { channel.close(); } catch (Exception ignored) {}
                        }
                    });
                } catch (Throwable t) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long n) {}
                        @Override public void cancel() {}
                    });
                    subscriber.onError(t);
                }
            }
        };
    }
}
