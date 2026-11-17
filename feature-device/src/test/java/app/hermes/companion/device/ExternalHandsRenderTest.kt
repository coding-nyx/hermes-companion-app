package app.hermes.companion.device

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.PairingPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExternalHandsRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "+night") fun externalDarkShowsActualOwnerAndNoLocalPermissionControls() {
        render("external-hands-dark")
    }

    @Test @Config(qualifiers = "+notnight") fun externalLightShowsActualOwnerAndNoLocalPermissionControls() {
        render("external-hands-light")
    }

    @Test @Config(qualifiers = "+night") fun externalLargeTextReflows() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        render("external-hands-large-text")
    }

    @Test @Config(qualifiers = "+night") fun externalRelayIdentityDarkRemainsEditable() {
        render("external-hands-identity-dark", openIdentity = true)
    }

    @Test @Config(qualifiers = "+notnight") fun externalRelayIdentityLightRemainsEditable() {
        render("external-hands-identity-light", openIdentity = true)
    }

    @Test @Config(qualifiers = "+night") fun externalRelayIdentityLargeTextReflows() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        render("external-hands-identity-large-text", openIdentity = true)
    }

    private fun render(name: String, openIdentity: Boolean = false) {
        var opened = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "coder", error = null,
                externalHands = true, externalHandsReachable = true, externalHandsArmed = true,
                externalHandsBackend = "hybrid", externalHandsTarget = "agent", externalHandsA11y = true,
                onPair = {}, onCancel = {}, onRevoke = {}, onOpenHands = { opened++ })
        } }
        rule.onNodeWithTag("device.hands.owner").assertTextContains("Hands")
        rule.onNodeWithTag("device.hands.status").assertTextContains("Armed in Hands")
        rule.onNodeWithTag("device.a11y.actual").assertDoesNotExist()
        rule.onNodeWithTag("device.permission.a11y").assertDoesNotExist()
        rule.onNodeWithTag("device.hands.launch").performScrollTo().performClick()
        assertEquals(1, opened)
        if (openIdentity) {
            rule.onNodeWithTag("device.identity").performScrollTo().performClick()
            rule.onNodeWithTag("device.label").assertExists()
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = android.graphics.Canvas(bitmap)
            if (!openIdentity) view.draw(canvas) else {
                val globalClass = Class.forName("android.view.WindowManagerGlobal")
                val global = globalClass.getDeclaredMethod("getInstance").invoke(null)
                val field = globalClass.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val roots = field.get(global) as List<android.view.View>
                check(roots.size > 1) { "No sheet window found" }
                roots.forEach { root ->
                    val position = IntArray(2)
                    root.getLocationOnScreen(position)
                    canvas.save()
                    canvas.translate(position[0].toFloat(), position[1].toFloat())
                    root.draw(canvas)
                    canvas.restore()
                }
            }
        }
        val file = java.io.File("build/reports/device-render/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
