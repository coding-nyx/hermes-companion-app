package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.hermes.companion.design.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.*
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w360dp-h720dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionModeRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val choices = mutableListOf<String>()
    private fun mount(paired: Boolean = true, configurable: Boolean = true) {
        compose.setContent { CompanionTheme {
            Column(Modifier.fillMaxSize().background(CompanionColor.Void)) {
                ConnectionModePreference("standalone", configurable, paired, false, null, choices::add)
            }
        } }
        compose.onNodeWithTag("connection.mode.open").performClick()
        compose.onNodeWithTag("connection.mode.proxy").performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val global = Class.forName("android.view.WindowManagerGlobal").getDeclaredMethod("getInstance").invoke(null)
            val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val roots = field.get(global) as List<android.view.View>
            val view = compose.activity.window.decorView
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(b)
            roots.forEach { root -> val pos = IntArray(2); root.getLocationOnScreen(pos); canvas.save(); canvas.translate(pos[0].toFloat(), pos[1].toFloat()); root.draw(canvas); canvas.restore() }
            File("/tmp/hermes-connection-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun pairedOperatorExplicitlyAppliesTheSelectedHostMode() {
        mount(); capture("dark")
        compose.onNodeWithTag("connection.mode.apply").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("proxy"), choices)
    }
    @Test fun pairingDoesNotGrantAnUnpairedPhoneHostConfiguration() {
        mount(paired = false)
        compose.onNodeWithTag("connection.mode.apply").performScrollTo().assertIsNotEnabled()
        assertTrue(choices.isEmpty())
    }
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xhdpi")
    fun currentPluginIsRequiredAndTheSheetUsesTheLightTheme() {
        mount(configurable = false); capture("light")
        compose.onNodeWithTag("connection.mode.apply").performScrollTo().assertIsNotEnabled()
    }
    @Test fun largeTextKeepsTheApplyActionReachable() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        mount(); compose.onNodeWithTag("connection.mode.apply").performScrollTo().assertIsDisplayed()
        capture("large")
    }
}
