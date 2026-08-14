package dev.simplesync.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SyncLoggerTest {

    @Test
    void testFormatMessage_WithPlaceholders() {
        String result = SyncLogger.formatMessage("Uploading world '{}' (size: {} bytes)", "MyWorld", 1024L);
        assertEquals("Uploading world 'MyWorld' (size: 1024 bytes)", result);
    }

    @Test
    void testFormatMessage_NoArgs() {
        String result = SyncLogger.formatMessage("Simple message");
        assertEquals("Simple message", result);
    }

    @Test
    void testFormatMessage_WithThrowable() {
        Exception ex = new RuntimeException("connection timeout");
        String result = SyncLogger.formatMessage("Failed to upload '{}'", "MyWorld", ex);
        assertTrue(result.contains("Failed to upload 'MyWorld'"));
        assertTrue(result.contains("RuntimeException: connection timeout"));
    }

    @Test
    void testLoggingMethodsDoNotThrow() {
        assertDoesNotThrow(() -> {
            SyncLogger.info("Info test: {}", 123);
            SyncLogger.warn("Warn test: {}", "test");
            SyncLogger.error("Error test: {}", "something");
            SyncLogger.error("Error with exception", new RuntimeException("test ex"));
        });
    }
}
