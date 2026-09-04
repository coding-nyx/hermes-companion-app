package app.hermes.companion.domain

import app.hermes.companion.model.SnapshotNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactTreeTest {
    @Test
    fun refsAreSessionLocalEn() {
        assertEquals("e1", CompactTree.ref(0))
        assertEquals("e2", CompactTree.ref(1))
    }

    @Test
    fun clipsAndEncodes() {
        val nodes = (0 until 100).map { i ->
            SnapshotNode(ref = CompactTree.ref(i), role = "button", text = "n$i", clickable = true, bounds = listOf(0, i, 10, i + 1))
        }
        val clipped = CompactTree.clip(nodes)
        assertEquals(CompactTree.MAX_NODES, clipped.size)
        val json = CompactTree.json(nodes, app = "com.example.fixture", activity = ".Main", width = 1080, height = 2400)
        assertTrue(json.contains("\"ref\":\"e1\""))
        assertTrue(json.contains("com.example.fixture"))
        assertFalse(json.contains("\"ref\":\"e81\""))
        assertEquals(80, CompactTree.refsOf(nodes).size)
    }

    @Test
    fun includesSafeAreaWhenSpecified() {
        val nodes = listOf(
            SnapshotNode(ref = "e1", role = "button", text = "Submit", clickable = true, bounds = listOf(80, 400, 1000, 496))
        )
        val safe = app.hermes.companion.model.ScreenSafeArea(top = 104, bottom = 68, left = 0, right = 0)
        val json = CompactTree.json(nodes, app = "com.test", width = 1080, height = 2400, safeArea = safe)
        assertTrue(json.contains("\"safe_area\":{\"top\":104,\"bottom\":68,\"left\":0,\"right\":0}"))
    }
}
