package com.joel.litesail.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    @Test
    fun matchingVersionDoesNotOfferUpdate() {
        assertNull(AppUpdate.fromTag("v0.3.0", "0.3.0"))
    }

    @Test
    fun anyDifferentReleaseVersionOffersUpdate() {
        assertNotNull(AppUpdate.fromTag("v0.3.0", "0.2.0"))
        assertNotNull(AppUpdate.fromTag("v0.3.0", "0.4.0"))
    }

    @Test
    fun updateUsesProxiedApkForTheExactTag() {
        val update = requireNotNull(AppUpdate.fromTag("v0.3.1", "0.3.0"))
        assertEquals("LiteSail-v0.3.1.apk", update.fileName)
        assertEquals(
            "https://gh-proxy.org/https://github.com/DvShu/LiteSail/releases/download/v0.3.1/LiteSail-v0.3.1.apk",
            update.downloadUrl
        )
    }

    @Test
    fun unsupportedTagDoesNotBecomeAnApkUrl() {
        assertNull(AppUpdate.fromTag("nightly", "0.3.0"))
        assertNull(AppUpdate.fromTag("v0.3.1/other", "0.3.0"))
    }
}
