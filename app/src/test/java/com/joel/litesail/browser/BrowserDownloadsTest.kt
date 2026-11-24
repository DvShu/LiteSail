package com.joel.litesail.browser

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

    @Test
    fun searchKeepsOnlyMatchingTasksAndGroupsThemByLocalDate() {
        val morning = record(1, "报告.pdf", "2026-11-23T18:00:00Z")
        val afternoon = record(2, "报告草稿.PDF", "2026-11-24T06:00:00Z")
        val previousDay = record(3, "封面.png", "2026-11-23T04:00:00Z")

        assertEquals(
            listOf(
                DownloadHistoryRow.Day(LocalDate.of(2026, 11, 24)),
                DownloadHistoryRow.Task(afternoon),
                DownloadHistoryRow.Task(morning)
            ),
            downloadHistoryRows(listOf(morning, previousDay, afternoon), "pdf", "下载文件", ZoneOffset.ofHours(8))
        )
    }

    private fun record(id: Long, title: String, timestamp: String) = BrowserDownloadRecord(
        id, title, DownloadManager.STATUS_SUCCESSFUL, 10L, 10L, Instant.parse(timestamp).toEpochMilli()
    )
}
