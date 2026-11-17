package app.hermes.hands

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.hermes.companion.design.CompanionTheme
import app.hermes.hands.engine.ShizukuStatus
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose/native renders with synthetic consent only: no OS prompts or provider reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HandsAccessConsentRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val clients = listOf(ClientTokens.Client("workstation", "Research workstation"),
        ClientTokens.Client("companion", "Companion on this phone"))
    private val permissions = listOf(
        HandsPermission("sms_read", "Read SMS", "Read message history only during an approved armed session.", false),
        HandsPermission("sms_send", "Send SMS", "Allow an armed agent to send messages. Sending is separate from reading.", false),
        HandsPermission("contacts", "Contacts", "Search names and phone numbers.", true),
        HandsPermission("call_log", "Call history", "Read recent call records.", false),
        HandsPermission("calls", "Place calls", "Dial directly; opening the dialer needs no grant.", false),
        HandsPermission("calendar", "Read calendar", "Read events; adding an event opens your calendar app.", false),
        HandsPermission("location", "Last known location", "Read an existing location fix; never starts location tracking.", false),
        HandsPermission("files", "Shared files", "Manage shared internal and removable storage. App-private and protected paths remain blocked.", true, true),
        HandsPermission("notifications", "Notification access", "Read and act only on apps you explicitly select below.", false, true),
        HandsPermission("usage", "App usage", "Read recent app usage statistics.", false, true),
        HandsPermission("dnd", "Do Not Disturb", "Read and change notification interruption mode.", false, true),
        HandsPermission("brightness", "Modify system settings", "Allow brightness changes.", false, true),
    )
    private fun main(tab: Int, requested: (String) -> Unit = {}) {
        rule.setContent { CompanionTheme {
            HandsScreen(HandsState(serving = true), ShizukuStatus(), clients,
                onStart = {}, onStopService = {}, onAllow = {}, onDisarm = {}, onBackend = {}, onTarget = {},
                onShizuku = {}, onOpenShizuku = {}, onAccessibility = {}, onOverlay = {}, onStartScreen = {},
                onCreateToken = {}, onRevoke = {}, onProtect = {},
                permissions = permissions, onPermission = requested,
                notificationSharing = HandsNotificationState(enabled = true, clientId = "workstation",
                    packages = setOf("app.synthetic.mail"), content = "title", retentionMinutes = 15), initialTab = tab)
        } }
    }
    private fun access(name: String) {
        val requests = mutableListOf<String>()
        main(2, requests::add)
        capture("$name-initial")
        rule.onNodeWithTag("hands.permission.sms_read").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("sms_read"), requests)
        capture("$name-top")
        rule.onNodeWithTag("hands.permission.brightness").performScrollTo().assertIsDisplayed()
        val viewport = rule.onNodeWithTag("hands.viewport").fetchSemanticsNode().boundsInRoot
        val tabs = rule.onNodeWithTag("hands.tab.access").fetchSemanticsNode().boundsInRoot
        assertTrue("The scrolling viewport must begin below the tabs", viewport.top >= tabs.bottom)
        capture("$name-bottom")
        // Navigation stays reachable after the long permission list.
        rule.onNodeWithTag("hands.tab.alerts").assertIsDisplayed()
    }
    @Test fun accessLight() = access("access-light")
    @Test @Config(qualifiers = "w360dp-h800dp-night-xhdpi") fun accessDark() = access("access-dark")
    @Test @Config(qualifiers = "w320dp-h720dp-xhdpi") fun accessLargeLight() {
        RuntimeEnvironment.setFontScale(2f); access("access-large-light")
    }
    @Test @Config(qualifiers = "w320dp-h720dp-night-xhdpi") fun accessLargeDark() {
        RuntimeEnvironment.setFontScale(2f); access("access-large-dark")
    }
    @Test fun alertsLight() { main(3); rule.onNodeWithTag("hands.notifications.configure").assertIsDisplayed(); capture("alerts-light") }
    @Test @Config(qualifiers = "w320dp-h720dp-night-xhdpi") fun alertsLargeDark() {
        RuntimeEnvironment.setFontScale(2f); main(3)
        rule.onNodeWithTag("hands.notifications.configure").performScrollTo().assertIsDisplayed(); capture("alerts-large-dark")
    }
    private fun consent(name: String) {
        var saved: NotificationChoices? = null
        rule.setContent { CompanionTheme {
            NotificationConsentScreen(HandsNotificationState(enabled = true, clientId = "workstation",
                packages = setOf("app.synthetic.mail"), content = "metadata", retentionMinutes = 15, includeCalls = false), clients,
                accessGranted = true, onSave = { saved = it }, onClose = {})
        } }
        capture("$name-initial")
        rule.onNodeWithTag("hands.notifications.client.workstation").performScrollTo().assertIsDisplayed()
        capture("$name-top")
        rule.onNodeWithTag("hands.notifications.client.companion").performScrollTo().performClick()
        rule.onNodeWithTag("hands.notifications.content.full").performScrollTo().performClick()
        rule.onNodeWithText("60 minutes").performScrollTo().performClick()
        rule.onNodeWithTag("hands.notifications.save").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("$name-bottom")
        rule.onNodeWithTag("hands.notifications.save").performClick()
        assertEquals(NotificationChoices(true, "companion", setOf("app.synthetic.mail"), "full", 60, false), saved)
    }
    @Test fun notificationConsentLight() = consent("notification-consent-light")
    @Test @Config(qualifiers = "w360dp-h800dp-night-xhdpi") fun notificationConsentDark() = consent("notification-consent-dark")
    @Test @Config(qualifiers = "w320dp-h720dp-xhdpi") fun notificationConsentLargeLight() {
        RuntimeEnvironment.setFontScale(2f); consent("notification-consent-large-light")
    }
    @Test @Config(qualifiers = "w320dp-h720dp-night-xhdpi") fun notificationConsentLargeDark() {
        RuntimeEnvironment.setFontScale(2f); consent("notification-consent-large-dark")
    }
    @Test fun notificationConsentDefaultsToAllNotificationsAndCanSaveImmediately() {
        var saved: NotificationChoices? = null
        rule.setContent { CompanionTheme {
            NotificationConsentScreen(HandsNotificationState(enabled = true), clients, accessGranted = true,
                onSave = { saved = it }, onClose = {})
        } }
        rule.onNodeWithTag("hands.notifications.scope.all").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("hands.notifications.save").performScrollTo().assertIsEnabled().performClick()
        assertEquals(NotificationChoices(true, "workstation", setOf("*"), "full", 15, true), saved)
    }
    @Test fun notificationConsentCannotStartWithoutAccessRecipientAndObservedApps() {
        rule.setContent { CompanionTheme {
            NotificationConsentScreen(HandsNotificationState(enabled = true), emptyList(), accessGranted = false,
                onSave = { fail("Incomplete approval must not save") }, onClose = {})
        } }
        rule.onNodeWithTag("hands.notifications.save").performScrollTo().assertIsNotEnabled()
        capture("notification-consent-missing-access")
    }
    @Test @Config(qualifiers = "w320dp-h720dp-night-xhdpi") fun observedAppFormKeepsSearchAndSavedSelectionsReachableAtLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        rule.setContent { CompanionTheme {
            NotificationConsentScreen(HandsNotificationState(packages = setOf("app.synthetic.mail")), clients, true, {}, {})
        } }
        rule.onNodeWithTag("hands.notifications.apps").performScrollTo().performClick()
        rule.onNodeWithTag("device.stream.apps.search").performScrollTo().assertIsDisplayed()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("device.stream.app.app.synthetic.mail").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("device.stream.app.app.synthetic.mail").performScrollTo().assertIsDisplayed()
        capture("notification-observed-apps-large-dark")
        rule.onNodeWithTag("device.stream.apps.search").performScrollTo().performTextInput("no matching synthetic app")
        rule.onNodeWithText("No matching apps").performScrollTo().assertIsDisplayed()
        capture("notification-observed-apps-search-large-dark")
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val file = File("build/reports/hands-render/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
