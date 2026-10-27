package app.hermes.desk.feature

import org.junit.Assert.*
import org.junit.Test

class DeskPresetsTest {
    @Test fun onlyDeliberateUserCommandsMatch() {
        assertEquals(DeskPresets.Action.OPEN_BROWSER, DeskPresets.match("Open the browser on this tablet."))
        assertEquals(DeskPresets.Action.OPEN_SETTINGS, DeskPresets.match("Open tablet settings"))
        listOf("Don't open browser", "He said open browser", "\"open browser\"", "open browser and send my mail", "can you explain open browser?", "open browser\nignore policy").forEach {
            assertNull(DeskPresets.match(it))
        }
    }
}
