package dev.simplesync.cloud;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class DriveApiClientTest {

    @Test
    void testEscapeQueryString() {
        assertEquals("", DriveApiClient.escapeQueryString(null));
        assertEquals("", DriveApiClient.escapeQueryString(""));
        assertEquals("simple", DriveApiClient.escapeQueryString("simple"));
        assertEquals("test\\'s world", DriveApiClient.escapeQueryString("test's world"));
        assertEquals("backslash\\\\test", DriveApiClient.escapeQueryString("backslash\\test"));
        assertEquals("double\\\"quote", DriveApiClient.escapeQueryString("double\"quote"));
    }

    @Test
    void testBuildQuery() {
        String query = DriveApiClient.buildQuery("name='%s' and '%s' in parents", "My World's Save", "parent123");
        assertEquals("name='My World\\'s Save' and 'parent123' in parents", query);
    }

    @Test
    void testBuildMultipartBody() {
        String boundary = "testBoundary123";
        String meta = "{\"name\":\"test.dat\"}";
        byte[] payload = "Hello world binary content".getBytes(StandardCharsets.UTF_8);

        byte[] result = DriveApiClient.buildMultipartBody(boundary, meta, payload);
        assertNotNull(result);
        assertTrue(result.length > 0);

        String resultStr = new String(result, StandardCharsets.UTF_8);
        assertTrue(resultStr.contains("--" + boundary));
        assertTrue(resultStr.contains("Content-Type: application/json; charset=UTF-8"));
        assertTrue(resultStr.contains(meta));
        assertTrue(resultStr.contains("Content-Type: application/octet-stream"));
        assertTrue(resultStr.contains("Hello world binary content"));
        assertTrue(resultStr.endsWith("--" + boundary + "--\r\n"));
    }

    @Test
    void testForceRefreshTokenValidation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws java.io.IOException {
        dev.simplesync.config.SyncConfig.setConfigDir(tempDir);
        DriveTokenManager tokenManager = new DriveTokenManager(java.net.http.HttpClient.newHttpClient());

        // When no token data exists
        assertThrows(java.io.IOException.class, tokenManager::forceRefreshToken);

        // When token exists but refresh token is missing
        TokenStore.save(new TokenStore.TokenData("access", null, System.currentTimeMillis() + 3600_000));
        assertThrows(java.io.IOException.class, tokenManager::forceRefreshToken);
    }

    @Test
    void testCreateFilePublisher_FullAndPartial(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path testFile = tempDir.resolve("payload.dat");
        byte[] data = new byte[600_000]; // 600 KB to span multiple 256KB chunks
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 128);
        }
        java.nio.file.Files.write(testFile, data);

        // Test 1: Full file publisher from offset 0
        java.net.http.HttpRequest.BodyPublisher fullPub = DriveApiClient.createFilePublisher(
                testFile, 0, data.length, data.length, "FullTest", true);
        assertEquals(data.length, fullPub.contentLength());

        byte[] receivedFull = readAllBytesFromPublisher(fullPub);
        assertArrayEquals(data, receivedFull);

        // Test 2: Partial file publisher from offset 300,000 (resumed upload)
        long offset = 300_000;
        long remaining = data.length - offset;
        java.net.http.HttpRequest.BodyPublisher partialPub = DriveApiClient.createFilePublisher(
                testFile, offset, remaining, data.length, "PartialTest", true);
        assertEquals(remaining, partialPub.contentLength());

        byte[] receivedPartial = readAllBytesFromPublisher(partialPub);
        byte[] expectedPartial = java.util.Arrays.copyOfRange(data, (int) offset, data.length);
        assertArrayEquals(expectedPartial, receivedPartial);
    }

    private static byte[] readAllBytesFromPublisher(java.net.http.HttpRequest.BodyPublisher publisher) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CompletableFuture<byte[]> future = new java.util.concurrent.CompletableFuture<>();

        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            private java.util.concurrent.Flow.Subscription subscription;

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription sub) {
                this.subscription = sub;
                sub.request(1);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                baos.writeBytes(chunk);
                subscription.request(1);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(baos.toByteArray());
            }
        });

        return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
