package dev.simplesync.util;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

public class DesktopUtilTest {

    @Test
    void testIsAllowedGoogleUrl_ValidUrls() {
        assertTrue(DesktopUtil.isAllowedGoogleUrl(URI.create("https://accounts.google.com/o/oauth2/device/user")));
        assertTrue(DesktopUtil.isAllowedGoogleUrl(URI.create("https://console.cloud.google.com/apis/library/drive.googleapis.com")));
        assertTrue(DesktopUtil.isAllowedGoogleUrl(URI.create("https://console.cloud.google.com/auth/audience")));
        assertTrue(DesktopUtil.isAllowedGoogleUrl(URI.create("https://www.googleapis.com/drive/v3/files")));
        assertTrue(DesktopUtil.isAllowedGoogleUrl(URI.create("https://google.com/")));
    }

    @Test
    void testIsAllowedGoogleUrl_InvalidUrls() {
        assertFalse(DesktopUtil.isAllowedGoogleUrl(null));
        assertFalse(DesktopUtil.isAllowedGoogleUrl(URI.create("http://accounts.google.com/"))); // Insecure HTTP
        assertFalse(DesktopUtil.isAllowedGoogleUrl(URI.create("https://malicious-google.com/")));
        assertFalse(DesktopUtil.isAllowedGoogleUrl(URI.create("https://google.com.attacker.com/")));
        assertFalse(DesktopUtil.isAllowedGoogleUrl(URI.create("https://example.com/")));
        assertFalse(DesktopUtil.isAllowedGoogleUrl(URI.create("file:///etc/passwd")));
    }

    @Test
    void testOpenUrl_RejectsInvalidUrls() {
        assertFalse(DesktopUtil.openUrl(null));
        assertFalse(DesktopUtil.openUrl(""));
        assertFalse(DesktopUtil.openUrl("http://insecure.example.com"));
        assertFalse(DesktopUtil.openUrl("https://evil.com"));
    }
}
