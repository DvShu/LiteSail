package com.joel.litesail.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWindowsTest {
    @Test
    fun capsWindowCountAndKeepsSelectionWhenFull() {
        val windows = BrowserWindows()
        repeat(BrowserWindows.LIMIT - 1) { assertTrue(windows.add()) }
        val last = windows.selected

        assertFalse(windows.add())
        assertEquals(BrowserWindows.LIMIT, windows.items.size)
        assertSame(last, windows.selected)
    }

    @Test
    fun closingOtherWindowsKeepsTheSameActiveWindow() {
        val windows = BrowserWindows()
        repeat(2) { windows.add() }
        val selected = windows.selected

        assertFalse(windows.close(0))
        assertEquals(1, windows.selectedIndex)
        assertSame(selected, windows.selected)
    }

    @Test
    fun closingActiveWindowSelectsNeighborAndClosingLastReturnsHome() {
        val windows = BrowserWindows()
        val first = windows.selected
        windows.add()
        windows.add()
        val third = windows.selected
        assertTrue(windows.select(1))

        assertTrue(windows.close(1))
        assertSame(third, windows.selected)
        assertTrue(windows.close(1))
        assertSame(first, windows.selected)
        assertTrue(windows.close(0))
        assertEquals(1, windows.items.size)
        assertNotSame(first, windows.selected)
        assertTrue(windows.selected.home)
    }

    @Test
    fun retainsSmallAddressesAndBoundsLargeMetadata() {
        val window = BrowserWindows().selected
        window.rememberAddress("https://example.org/", "x".repeat(1_000))
        assertEquals("https://example.org/", window.url)
        assertEquals(256, window.title?.length)

        window.rememberAddress("https://example.org/" + "x".repeat(9_000), null)
        assertNull(window.url)
    }

    @Test
    fun closingAllWindowsClearsAddressDataAndLeavesOneHomeWindow() {
        val windows = BrowserWindows()
        windows.selected.rememberAddress("https://example.org/first", "First")
        assertTrue(windows.add())
        windows.selected.rememberAddress("https://example.org/second", "Second")
        val closed = windows.items.toList()

        windows.closeAll()

        assertEquals(1, windows.items.size)
        assertEquals(0, windows.selectedIndex)
        assertTrue(windows.selected.home)
        assertNull(windows.selected.url)
        closed.forEach {
            assertNull(it.url)
            assertNull(it.title)
        }
    }

    @Test
    fun repeatedWindowOperationsKeepCountBoundedAndClearClosedRecords() {
        val windows = BrowserWindows()
        repeat(1_000) {
            repeat(BrowserWindows.LIMIT - 1) { index ->
                assertTrue(windows.add())
                windows.selected.rememberAddress("https://example.org/$index", "Window $index")
            }
            assertFalse(windows.add())
            assertTrue(windows.select(0))
            assertTrue(windows.select(BrowserWindows.LIMIT - 1))

            while (windows.items.size > 1) {
                val closing = windows.selected
                assertTrue(windows.close(windows.selectedIndex))
                assertNull(closing.url)
                assertNull(closing.title)
            }
            assertEquals(1, windows.items.size)
            assertTrue(windows.selected.home)
        }
    }
}
