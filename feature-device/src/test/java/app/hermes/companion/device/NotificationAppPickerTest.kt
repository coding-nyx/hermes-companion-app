package app.hermes.companion.device

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
@Config(sdk = [31], qualifiers = "w360dp-h720dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationAppPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @org.junit.Before fun hideTestActionBar() { rule.activity.actionBar?.hide() }
    private val apps = listOf(NotificationApp("com.example.calendar", "Calendar"), NotificationApp("com.example.phone", "Phone"), NotificationApp("com.example.chat", "Messages"))

    @Test fun searchMatchesLabelsAndPackagesWithoutChangingSelection() {
        assertEquals(listOf(apps[1]), filterNotificationApps(apps, "PHONE"))
        assertEquals(listOf(apps[2]), filterNotificationApps(apps, "example.chat"))
        assertEquals(apps, filterNotificationApps(apps, " "))
    }

    @Test fun appSelectionIsExplicitAndCanBeRemoved() {
        var selected by mutableStateOf(setOf("com.example.phone"))
        rule.setContent { CompanionTheme {
            Column(Modifier.fillMaxSize().background(app.hermes.companion.design.CompanionColor.Void).verticalScroll(rememberScrollState()).padding(24.dp)) {
                NotificationAppGrid(apps, selected, { pkg, checked -> selected = if (checked) selected + pkg else selected - pkg })
            }
        } }
        rule.onNodeWithTag("device.stream.app.com.example.phone").assertIsOn().performClick().assertIsOff()
        rule.onNodeWithTag("device.stream.app.com.example.calendar").performClick().assertIsOn()
        assertEquals(setOf("com.example.calendar"), selected)
        rule.onNodeWithTag("device.stream.apps.count").assertTextContains("1 app selected")
        rule.onNodeWithContentDescription("com.example.calendar", useUnmergedTree = true).assertExists()
        capture("notification-app-picker")
        rule.onNodeWithTag("device.stream.apps.search").performTextInput("Messages")
        rule.onNodeWithTag("device.stream.app.com.example.calendar").assertDoesNotExist()
        rule.onNodeWithTag("device.stream.app.com.example.chat").assertIsDisplayed()
        assertEquals(setOf("com.example.calendar"), selected)
    }

    @Test fun largeTextPickerKeepsRemovalAccessible() {
        var selected by mutableStateOf(setOf("com.example.phone"))
        rule.setContent { CompanionTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                Column(Modifier.fillMaxSize().background(app.hermes.companion.design.CompanionColor.Void).verticalScroll(rememberScrollState()).padding(24.dp)) {
                    NotificationAppGrid(apps, selected, { pkg, checked -> selected = if (checked) selected + pkg else selected - pkg })
                }
            }
        } }
        rule.onNodeWithTag("device.stream.app.com.example.phone").performScrollTo().assertIsOn()
        capture("notification-app-picker-large-text")
        rule.onNodeWithTag("device.stream.app.com.example.phone").performClick().assertIsOff()
        assertEquals(emptySet<String>(), selected)
    }

    @Test fun startingSharingRequiresDestinationAndAppSelection() {
        var starts = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "assistant", error = null,
                onPair = {}, onCancel = {}, onRevoke = {}, onToggleNotifStream = { starts++ })
        } }
        rule.onNodeWithTag("device.stream.open").performScrollTo().performClick()
        rule.onNodeWithTag("device.stream").assertIsNotEnabled()
        assertEquals(0, starts)
    }

    @Test fun selectedAppsAndRouteCannotEnableWithoutAndroidAccess() {
        var starts = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "assistant", error = null,
                nlsBound = false, notifStreamOrigin = "https://gateway.example", notifStreamProfile = "assistant",
                notifStreamApps = setOf("com.example.calendar"), onPair = {}, onCancel = {}, onRevoke = {},
                onToggleNotifStream = { starts++ })
        } }
        rule.onNodeWithTag("device.stream.open").performScrollTo().performClick()
        rule.onNodeWithTag("device.stream").assertIsNotEnabled()
        rule.onNodeWithText("Allow notification access before starting sharing").assertIsDisplayed()
        assertEquals(0, starts)
    }

    @Test fun configuredStreamRequiresConfirmationBeforeEnabling() {
        var starts = 0
        rule.setContent { CompanionTheme {
            DeviceScreen(PairingPhase.PAIRED, "", "phone", deviceProfileId = "assistant", error = null,
                nlsBound = true, notifStreamOrigin = "https://gateway.example", notifStreamProfile = "assistant",
                notifStreamApps = setOf("com.example.calendar"), onPair = {}, onCancel = {}, onRevoke = {},
                onToggleNotifStream = { starts++ })
        } }
        rule.onNodeWithTag("device.stream.open").performScrollTo().performClick()
        rule.onNodeWithTag("device.stream").performClick()
        assertEquals(0, starts)
        rule.onNodeWithText("Start notification sharing?").assertIsDisplayed()
        capture("notification-sharing-confirmation")
        rule.onNodeWithTag("device.stream.confirm").performClick()
        assertEquals(1, starts)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = android.graphics.Canvas(bitmap)
            val roots = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 }
            if (roots.isEmpty()) view.draw(canvas) else roots.forEach { it.draw(canvas) }
        }
        val file = java.io.File("build/reports/device-render/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
