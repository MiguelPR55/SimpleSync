package dev.simplesync.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class RetryUtilTest {

    @Test
    void testFormEncode_SimpleAndSpecialCharacters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", "12345");
        params.put("scope", "https://www.googleapis.com/auth/drive.file");
        params.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        params.put("empty_param", "");
        params.put("null_param", null);

        String encoded = RetryUtil.formEncode(params);

        assertTrue(encoded.contains("client_id=12345"));
        assertTrue(encoded.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"));
        assertTrue(encoded.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));
        assertFalse(encoded.contains("empty_param"));
        assertFalse(encoded.contains("null_param"));
    }

    @Test
    void testRetry_SucceedsOnFirstAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryUtil.retry(3, "TestOp", () -> {
            attempts.incrementAndGet();
            return "success";
        });

        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void testRetry_SucceedsOnRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryUtil.retry(3, "TestOp", () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IOException("Transient network error");
            }
            return "success after retry";
        });

        assertEquals("success after retry", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void testRetry_FailsAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThrows(IOException.class, () -> RetryUtil.retry(3, "TestOp", () -> {
            attempts.incrementAndGet();
            throw new IOException("Persistent error");
        }));

        assertEquals(3, attempts.get());
    }

    @Test
    void testRetry_FatalAuthErrorDoesNotRetry() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThrows(IOException.class, () -> RetryUtil.retry(3, "AuthOp", () -> {
            attempts.incrementAndGet();
            throw new IOException("invalid_grant: Token has been revoked");
        }));

        assertEquals(1, attempts.get(), "Fatal auth error (invalid_grant) must not trigger retries");
    }
}
