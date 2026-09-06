package app.hermes.companion.domain

import app.hermes.companion.model.SavedGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayBookTest {
    @Test
    fun mergesSavedPairedAndLastOkWithoutDupes() {
        val rows = GatewayBook.merge(
            saved = listOf(
                SavedGateway("lab", "lab", "http://100.85.151.99:9120"),
                SavedGateway("hub", "hub-11", "http://100.88.4.63:9120/"),
            ),
            pairedOrigins = listOf("http://100.85.151.99:9120"),
            lastGoodOrigin = "http://100.88.4.63:9120",
        )
        assertEquals(listOf("hub-11", "lab"), rows.map { it.name })
        val hub = rows.first { it.name == "hub-11" }
        assertTrue(hub.lastOk)
        assertTrue(hub.forgettable)
        assertFalse(hub.paired)
        val lab = rows.first { it.name == "lab" }
        assertTrue(lab.paired)
        assertFalse(lab.forgettable)
        assertFalse(lab.lastOk)
    }

    @Test
    fun pairedOriginAppearsEvenIfNeverSaved() {
        val rows = GatewayBook.merge(
            saved = emptyList(),
            pairedOrigins = listOf("http://lab.ts.net:9120"),
            lastGoodOrigin = null,
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single().paired)
        assertFalse(rows.single().forgettable)
        assertEquals("lab.ts.net", rows.single().host)
    }

    @Test
    fun lastGoodOnlyIsNotForgettable() {
        val rows = GatewayBook.merge(
            saved = emptyList(),
            pairedOrigins = emptyList(),
            lastGoodOrigin = "http://hub:9120",
        )
        assertTrue(rows.single().lastOk)
        assertFalse(rows.single().forgettable)
    }

    @Test
    fun markHealthSetsDownWithoutDroppingChips() {
        val base = GatewayBook.merge(
            saved = listOf(
                SavedGateway("a", "lab", "http://lab:9120"),
                SavedGateway("b", "hub", "http://hub:9120"),
            ),
            pairedOrigins = emptyList(),
            lastGoodOrigin = "http://lab:9120",
        )
        val marked = GatewayBook.markHealth(
            base,
            upKeys = setOf(GatewayBook.key("http://lab:9120")),
            downKeys = setOf(GatewayBook.key("http://hub:9120")),
        )
        assertEquals("up", marked.first { it.name == "lab" }.health)
        assertEquals("down", marked.first { it.name == "hub" }.health)
        assertEquals(2, marked.size)
    }
}
