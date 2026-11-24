package com.joel.litesail.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class BrowserHistoryTest {
    @Test
    fun searchMatchesTitlesAndUrlsWithinTheSameLocalDay() {
        val titleMatch = visit("https://example.org/", "LiteSail 更新", "2026-11-24T06:00:00Z")
        val urlMatch = visit("https://example.org/LiteSail", "发布记录", "2026-11-23T18:00:00Z")
        val unrelated = visit("https://example.org/other", "其他网页", "2026-11-23T04:00:00Z")

        assertEquals(
            listOf(
                BrowserLibraryRow.Day(LocalDate.of(2026, 11, 24)),
                BrowserLibraryRow.Visit(titleMatch),
                BrowserLibraryRow.Visit(urlMatch)
            ),
            browserHistoryRows(listOf(titleMatch, urlMatch, unrelated), "litesail", ZoneOffset.ofHours(8))
        )
    }

    private fun visit(url: String, title: String, timestamp: String) =
        BrowserVisit(url, title, Instant.parse(timestamp).toEpochMilli())
}
