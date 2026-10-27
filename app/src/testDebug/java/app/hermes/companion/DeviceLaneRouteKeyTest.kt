package app.hermes.companion

import org.junit.Assert.*
import org.junit.Test

class DeviceLaneRouteKeyTest {
    @Test fun proxyPathsDoNotCollapseToOneLane() {
        assertNotEquals(deviceLaneKey("https://gateway.example/one"), deviceLaneKey("https://gateway.example/two"))
        assertEquals(deviceLaneKey("https://gateway.example:443/one/"), deviceLaneKey("https://GATEWAY.example/one"))
    }
    @Test fun invalidRoutesAreNotLaneKeys() {
        assertEquals("", deviceLaneKey("https://user:secret@gateway.example"))
        assertEquals("", deviceLaneKey("https://gateway.example?retarget=1"))
    }
}
