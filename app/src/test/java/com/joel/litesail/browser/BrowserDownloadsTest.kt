package com.joel.litesail.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadsTest {
    @Test
    fun keepsAttachmentNameInsideDownloadsDirectory() {
        assertEquals("报告.pdf", downloadFileName("../images\\报告.pdf"))
        assertEquals("download", downloadFileName(".."))
        assertEquals("report_.pdf", downloadFileName("report\u0000.pdf"))
    }

    @Test
    fun shortensMultibyteNameAndPreservesExtension() {
        val name = downloadFileName("文".repeat(100) + ".pdf")

        assertTrue(name.toByteArray().size <= 240)
        assertTrue(name.endsWith(".pdf"))
    }
}
