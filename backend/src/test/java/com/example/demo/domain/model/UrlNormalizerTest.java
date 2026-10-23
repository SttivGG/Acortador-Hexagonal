package com.example.demo.domain.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UrlNormalizerTest {
    @Test
    void foldsOnlySafeEquivalences() {
        assertEquals("https://example.com/", UrlNormalizer.normalize(" HTTPS://EXAMPLE.COM:443 "));
        assertEquals("http://example.com/", UrlNormalizer.normalize("http://EXAMPLE.com:80/"));
        assertEquals("http://[::1]/", UrlNormalizer.normalize("http://[::1]:80"));
    }

    @Test
    void preservesPathQueryFragmentEscapesAndNonDefaultPorts() {
        for (String url : new String[]{"https://example.com/A", "https://example.com/a/",
                "https://example.com/a?b=2&a=1", "https://example.com/a?a=1&b=2",
                "https://example.com/a#section", "https://example.com/%2f", "https://example.com:8443/",
                "https://example.com/a/../b"}) assertEquals(url, UrlNormalizer.normalize(url));
    }

    @Test
    void rejectsInvalidAndCredentialUrls() {
        for (String url : new String[]{"javascript:alert(1)", "ftp://example.com/", "https://user:pass@example.com/",
                "https://", "https://example.com:70000/", ""}) {
            assertThrows(IllegalArgumentException.class, () -> UrlNormalizer.normalize(url));
        }
    }
}
