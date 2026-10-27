package app.hermes.companion.domain

import org.junit.Assert.*
import org.junit.Test

class NativeGroupNamesTest {
    @Test fun blankNamesDefaultToSelectedBotNames() {
        assertEquals("Hermes, Research", NativeGroupNames.resolve("", listOf("Hermes", "Research")))
        assertEquals("Hermes, Research", NativeGroupNames.resolve("   ", listOf("Hermes", "Research")))
        assertEquals("Release team", NativeGroupNames.resolve("  Release team  ", listOf("Hermes", "Research")))
    }

    @Test fun repeatedGroupsReceiveAFreshNumberedName() {
        assertEquals("Hermes, Research 3", NativeGroupNames.resolve("", listOf("Hermes", "Research"),
            setOf("Hermes, Research", "Hermes, Research 2")))
    }

    @Test fun LengthAndSuffixStayWithinDesktopLimitWithoutSplittingEmoji() {
        val base = "a".repeat(63) + "🤖"
        assertEquals("a".repeat(63), NativeGroupNames.limit(base))
        val fresh = NativeGroupNames.resolve(base, emptyList(), setOf("a".repeat(63)))
        assertTrue(fresh.length <= 64)
        assertTrue(fresh.endsWith(" 2"))
        assertFalse(fresh.any { Character.isSurrogate(it) })
    }
}
