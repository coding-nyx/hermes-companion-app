package app.hermes.companion

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class DesignThemeRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultTextIsReadableWhilePrimaryButtonsKeepDarkLabels() {
        rule.setContent { CompanionTheme { Column {
            Text("Canvas text")
            Button(onClick = {}) { Text("Primary action") }
        } } }
        fun textColor(label: String): androidx.compose.ui.graphics.Color {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        assertEquals(CompanionColor.Text, textColor("Canvas text"))
        assertEquals(CompanionColor.Void, textColor("Primary action"))
    }
}
