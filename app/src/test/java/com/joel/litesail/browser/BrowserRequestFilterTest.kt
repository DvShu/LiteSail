package com.joel.litesail.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRequestFilterTest {
    private val filter = BrowserRequestFilter(adBlockingEnabled = true)

    @Test
    fun matchesExactAdHostsForHttpAndHttpsResources() {
        assertTrue(filter.blocksResource("https", "PAGEAD2.GOOGLESYNDICATION.COM.", isMainFrame = false))
        assertTrue(filter.blocksResource("http", "mi.gdt.qq.com", isMainFrame = false))
        assertFalse(filter.blocksResource("https", "g.doubleclick.net.example.org", isMainFrame = false))
        assertFalse(filter.blocksResource("https", "www.qq.com", isMainFrame = false))
    }

    @Test
    fun leavesMainFrameAndNonWebResourcesToWebView() {
        assertFalse(filter.blocksResource("https", "g.doubleclick.net", isMainFrame = true))
        assertFalse(filter.blocksResource("file", "g.doubleclick.net", isMainFrame = false))
        assertFalse(filter.blocksResource("https", null, isMainFrame = false))
    }

    @Test
    fun disablingAdBlockingKeepsExternalNavigationBlocked() {
        filter.adBlockingEnabled = false

        assertFalse(filter.blocksResource("https", "g.doubleclick.net", isMainFrame = false))
        assertTrue(filter.blocksNavigation("tel"))
        assertTrue(filter.blocksNavigation("intent"))
        assertFalse(filter.blocksNavigation("http"))
        assertFalse(filter.blocksNavigation("https"))
    }
}
