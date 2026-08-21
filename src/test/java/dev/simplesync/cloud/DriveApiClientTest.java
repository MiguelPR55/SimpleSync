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
}
