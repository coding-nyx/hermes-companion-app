package app.hermes.hands.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnginePolicyTest {
    @Test fun packageNamesCannotBecomeShellArguments() {
        assertEquals("org.example.app", EnginePolicy.packageName("org.example.app"))
        listOf("org.example;reboot", "-n", "org.example app", "org.example/Activity").forEach {
            assertThrows(IllegalArgumentException::class.java) { EnginePolicy.packageName(it) }
        }
    }

    @Test fun coordinatesMustBelongToTheSelectedDisplay() {
        assertEquals("719.0", EnginePolicy.coordinate(719.0, 720))
        listOf(-1.0, 720.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { EnginePolicy.coordinate(it, 720) }
        }
    }

    @Test fun textIsExplicitAboutTheAndroidKeymapLimit() {
        assertEquals("hello%sworld", EnginePolicy.text("hello world"))
        listOf("line\nbreak", "你好", "literal%s", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { EnginePolicy.text(it) }
        }
        // Metacharacters remain one ProcessBuilder argv entry, never executed by a shell.
        assertEquals("$(reboot);`id`", EnginePolicy.text("$(reboot);`id`"))
    }

    @Test fun displayAllocationIsBounded() {
        EnginePolicy.dimensions(720, 1280, 240)
        assertThrows(IllegalArgumentException::class.java) { EnginePolicy.dimensions(1600, 2400, 240) }
        assertThrows(IllegalArgumentException::class.java) { EnginePolicy.dimensions(720, 1280, 0) }
    }
}
