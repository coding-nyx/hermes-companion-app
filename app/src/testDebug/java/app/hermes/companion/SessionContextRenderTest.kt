package app.hermes.companion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import app.hermes.companion.model.*
import app.hermes.companion.settings.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SessionContextRenderTest {
    @get:Rule val compose = createComposeRule()
    private val session = SessionRef("s1", "coder", "Synthetic context", 0)
    @Test fun contextMenuDismissesActionsAndOpensContext() {
        var selected = 0; var dismissed = 0
        compose.setContent { SessionBottomSheet(session, false, false, {}, {}, {}, {}, { dismissed++ }, { selected++ }) }
        compose.onNodeWithText("Context usage").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, selected); assertEquals(1, dismissed)
    }
    @Test fun explicitZeroIsShownInsteadOfEstimatedTotal() {
        compose.setContent { SessionContextBottomSheet(SessionContextPanel("http://lab", session, 1, false,
            SessionContext(0, 1000, 900, false, "provider_usage", "model", emptyList())), {}, {}) }
        compose.onNodeWithText("0 / 1,000 tokens").assertExists()
        compose.onNodeWithText("Provider-reported usage").assertExists()
        compose.onNodeWithText("Category breakdown is not available yet.").assertExists()
    }
    @Test fun unknownUsageAndRetryRemainReadable() {
        var refreshed = 0
        compose.setContent { SessionContextBottomSheet(SessionContextPanel("http://lab", session, 1, false,
            error = "Login expired"), { refreshed++ }, {}) }
        compose.onNodeWithText("Login expired").assertExists()
        compose.onNodeWithText("Refresh context").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, refreshed)
    }
}
