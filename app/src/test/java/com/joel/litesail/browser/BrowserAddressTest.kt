package com.joel.litesail.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAddressTest {
    @Test fun completeHttpAndHttpsUrlsArePreserved() {
        assertEquals("http://example.com:8080/a#section", BrowserAddress.resolve("HTTP://example.com:8080/a#section"))
        assertEquals("https://example.com/?x=1", BrowserAddress.resolve(" https://example.com/?x=1 "))
    }

    @Test fun bareHostUsesHttps() {
        assertEquals("https://example.org/docs", BrowserAddress.resolve("example.org/docs"))
        assertEquals("https://localhost:8080/", BrowserAddress.resolve("localhost:8080/"))
        assertEquals("https://[::1]:8080/", BrowserAddress.resolve("[::1]:8080/"))
    }

    @Test fun searchInputIsEncoded() {
        assertEquals("https://www.baidu.com/s?wd=%E8%BD%BB%E8%88%9F+%26+Android", BrowserAddress.resolve("轻舟 & Android"))
        assertEquals(
            "https://www.bing.com/search?q=%E8%BD%BB%E8%88%9F+%26+Android",
            BrowserAddress.resolve("轻舟 & Android", BrowserAddress.BING_SEARCH_URL_PREFIX)
        )
    }

    @Test fun nonWebAndMalformedExplicitUrlsAreRejected() {
        listOf("  ", "javascript:alert(1)", "file:///etc/hosts", "intent://example.org", "https://", "https://user@example.org").forEach {
            assertNull(it, BrowserAddress.resolve(it))
        }
    }

    @Test fun onlyHttpAndHttpsCanNavigate() {
        assertTrue(BrowserAddress.isWebScheme("https"))
        assertTrue(BrowserAddress.isWebScheme("http"))
        assertFalse(BrowserAddress.isWebScheme("content"))
        assertFalse(BrowserAddress.isWebScheme(null))
    }

    @Test fun scannedWebAddressesUseTheSameValidation() {
        assertEquals("http://example.org/page", BrowserAddress.scannedWebUrl(" HTTP://example.org/page "))
        assertEquals("https://example.org/docs", BrowserAddress.scannedWebUrl("example.org/docs"))
        assertNull(BrowserAddress.scannedWebUrl("https://user@example.org"))
        assertNull(BrowserAddress.scannedWebUrl("https:80"))
        assertNull(BrowserAddress.resolve("https:80"))
    }

    @Test fun scannedTextsUseBingWithoutTreatingOtherSchemesAsLinks() {
        for (text in listOf("轻舟 & Android", "javascript:alert(1)", "mailto:user@example.org", "https://")) {
            assertNull(text, BrowserAddress.scannedWebUrl(text))
        }
        assertEquals(
            "https://www.bing.com/search?q=%E8%BD%BB%E8%88%9F+%26+Android",
            BrowserAddress.bingSearchUrl(" 轻舟 & Android ")
        )
        assertEquals(
            "https://www.bing.com/search?q=mailto%3Auser%40example.org",
            BrowserAddress.bingSearchUrl("mailto:user@example.org")
        )
    }
}
