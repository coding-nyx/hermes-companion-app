package app.hermes.hands

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArmSelectionTest {
    @Test fun omittedOrMatchingFieldsUseTheCurrentSelection() {
        assertNull(armSelectionMismatch(JSONObject(), "primary", "hybrid"))
        assertNull(armSelectionMismatch(JSONObject().put("target", "primary"), "primary", "hybrid"))
        assertNull(armSelectionMismatch(JSONObject().put("backend", "hybrid"), "primary", "hybrid"))
        assertNull(armSelectionMismatch(JSONObject().put("target", "primary").put("backend", "hybrid"), "primary", "hybrid"))
    }

    @Test fun targetMismatchReportsTheActualSelectionWithoutAskingForApproval() {
        val response = armSelectionMismatch(JSONObject().put("target", "agent"), "primary", "hybrid")!!
        assertFalse(response.getBoolean("ok"))
        val detail = response.getJSONObject("error")
        assertEquals("target_mismatch", detail.getString("code"))
        assertTrue(detail.getString("message").contains("target=primary"))
        assertTrue(detail.getString("message").contains("Omit target"))
        assertFalse(detail.getString("message").contains("approval", ignoreCase = true))
    }

    @Test fun backendMismatchReportsTheActualSelectionWithoutAskingForApproval() {
        val response = armSelectionMismatch(JSONObject().put("backend", "shizuku"), "primary", "hybrid")!!
        val detail = response.getJSONObject("error")
        assertEquals("backend_mismatch", detail.getString("code"))
        assertTrue(detail.getString("message").contains("backend=hybrid"))
        assertTrue(detail.getString("message").contains("Omit backend"))
        assertFalse(detail.getString("message").contains("approval", ignoreCase = true))
    }
}
