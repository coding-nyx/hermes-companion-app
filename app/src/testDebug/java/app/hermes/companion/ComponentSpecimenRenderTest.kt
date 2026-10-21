package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual reusable Compose elements with clearly synthetic, non-connected preview values. */
@OptIn(ExperimentalLayoutApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class,
    qualifiers = "w412dp-h1080dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentSpecimenRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(name: String) {
        rule.activity.actionBar?.hide()
        rule.setContent {
            CompanionTheme {
                Column(
                    Modifier.fillMaxSize().background(CompanionColor.Void)
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SignalSignature(label = "HERMES / DESIGN SYSTEM")
                    Text("Signal Deck", style = CompanionType.Display)
                    Text("Component preview · sample values, no connected host.",
                        style = CompanionType.Caption)
                    SectionHeader("Context controls")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ContextControl("Host", "Lab", {}, Modifier.weight(1f))
                        ContextControl("Agent", "Research", {}, Modifier.weight(1f), accent = CompanionColor.Accent)
                    }
                    SectionHeader("Actions")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("New chat", {}, kind = ActionKind.PRIMARY)
                        ActionButton("Details", {})
                        ActionButton("Stop", {}, kind = ActionKind.DANGER)
                    }
                    GroupedSection("Honest status", padded = true) {
                        ProtocolStatus("Native chat", "Connected · sample", tone = CompanionColor.Signal)
                        ProtocolStatus("Agent profile", "Research selected · sample", tone = CompanionColor.Accent)
                        ProtocolStatus("Permission", "Needs attention · sample", tone = CompanionColor.Warn)
                    }
                    GroupedSection("Access controls") {
                        PreferenceRow("Accessibility", "Screen control only when Hands is armed",
                            value = "Allowed", selected = true, onClick = {})
                        PreferenceToggle("Decorative motion", false, {},
                            summary = "Optional effects are off by default")
                    }
                    Text("Readable first. Neon second.", style = CompanionType.Body)
                    Text("48 dp minimum targets · system light/dark · static by default",
                        style = CompanionType.Caption)
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Context controls").assertIsDisplayed()
        rule.onNodeWithContentDescription("Host: Lab. Change Host").assertHasClickAction()
        rule.onNodeWithText("New chat").assertHasClickAction()
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = Canvas(bitmap)
            val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .filter { it.isShown && it.width > 0 }
            if (roots.isEmpty()) decor.draw(canvas) else roots.forEach { it.draw(canvas) }
        }
        File("build/reports/cyberpunk-elements/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun darkComponentSpecimen() = render("elements-dark")

    @Test
    @Config(qualifiers = "w412dp-h1080dp-notnight-xhdpi")
    fun lightComponentSpecimen() = render("elements-light")
}
