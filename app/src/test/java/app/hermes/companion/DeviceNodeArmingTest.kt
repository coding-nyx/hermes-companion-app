package app.hermes.companion

import android.Manifest
import android.app.Application
import android.content.Context
import org.robolectric.RuntimeEnvironment
import android.provider.Settings
import app.hermes.companion.data.local.DeviceCredStore
import app.hermes.companion.data.local.StickyStore
import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.DeviceCommand
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceNodeArmingTest {
    private fun testApp() = CompanionApp().apply {
        Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
            .invoke(this, RuntimeEnvironment.getApplication())
    }

    private fun connect(service: CompanionAccessibilityService) {
        CompanionAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }
    private val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private val other = "com.reader/.TalkBack"

    private fun coordinator(app: CompanionApp, scope: TestScope): DeviceNodeCoordinator {
        val sticky = StickyStore(app).apply { keepA11yOn = true; stayConnected = false; notifStreamEnabled = false }
        return DeviceNodeCoordinator(app, HostClientPool(), DeviceCredStore(app.getSharedPreferences("arm-test", 0)), sticky, scope.backgroundScope)
    }

    @Test fun armWaitsForConnectionAndDisarmCancelsPendingSuccess() = runTest {
        val app = testApp()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(app.contentResolver, key, other)
        val node = coordinator(app, this)
        node.arm()
        runCurrent()
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
        assertTrue(Settings.Secure.getString(app.contentResolver, key).contains("CompanionAccessibilityService"))
        node.disarm()
        assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
        val service = Robolectric.buildService(CompanionAccessibilityService::class.java).create().get()
        try {
            connect(service)
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(DeviceArm.DISARMED, node.state.value.arm)
            node.recoverAccessibility()
            assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
        } finally { service.onDestroy() }
    }

    @Test fun connectionCompletesArmAndDisarmPreservesOtherService() = runTest {
        val app = testApp()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(app.contentResolver, key, other)
        val node = coordinator(app, this)
        node.arm()
        runCurrent()
        val service = Robolectric.buildService(CompanionAccessibilityService::class.java).create().get()
        try {
            connect(service)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(DeviceArm.ARMED, node.state.value.arm)
            node.disarm()
            assertEquals(DeviceArm.DISARMED, node.state.value.arm)
            assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
        } finally { service.onDestroy() }
    }

    @Test fun connectionTimeoutRollsBackAndKeepOnCannotReenable() = runTest {
        val app = testApp()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(app.contentResolver, key, other)
        val node = coordinator(app, this)
        node.arm()
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
        node.recoverAccessibility()
        assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
    }

    @Test fun sameLaneDisarmBypassesPendingArmAcknowledgement() = runTest {
        val app = testApp()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(app.contentResolver, key, other)
        val node = coordinator(app, this)
        val commands = Channel<DeviceCommand>(Channel.UNLIMITED)
        val replies = mutableListOf<Pair<String, Boolean>>()
        backgroundScope.launch {
            dispatchArmCommands(commands.receiveAsFlow(), { error("Unexpected duplicate ARM") }) { command ->
                when (command.action) {
                    "device.arm" -> { node.arm(); node.awaitArm(); replies += "arm" to (node.state.value.arm != DeviceArm.DISARMED) }
                    "device.disarm" -> { node.disarm(); replies += "disarm" to false }
                }
            }
        }
        commands.send(DeviceCommand(commandId = "arm-1", action = "device.arm"))
        runCurrent()
        assertTrue(node.state.value.armPending)
        assertTrue(replies.isEmpty())
        commands.send(DeviceCommand(commandId = "disarm-1", action = "device.disarm"))
        runCurrent()
        assertFalse(node.state.value.armPending)
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
        assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
        assertTrue(replies.contains("disarm" to false))
        assertTrue(replies.contains("arm" to false))
        val service = Robolectric.buildService(CompanionAccessibilityService::class.java).create().get()
        try {
            connect(service)
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(DeviceArm.DISARMED, node.state.value.arm)
            assertFalse(replies.contains("arm" to true))
        } finally { service.onDestroy() }
    }

    @Test fun explicitOffPersistsCancelsArmAndBlocksRecoveryAndRemoteArmUntilOn() = runTest {
        val app = testApp()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(app.contentResolver, key, other)
        val node = coordinator(app, this)
        node.arm()
        runCurrent()
        assertTrue(node.state.value.armPending)
        assertTrue(node.turnAccessibilityOff())
        assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
        assertTrue(node.state.value.a11yOffRequested)
        assertFalse(node.state.value.armPending)
        node.recoverAccessibility()
        node.arm()
        runCurrent()
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
        val restarted = coordinator(app, this)
        assertTrue(restarted.state.value.a11yOffRequested)
        restarted.arm()
        restarted.recoverAccessibility()
        runCurrent()
        assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
        assertTrue(restarted.turnAccessibilityOn())
        assertFalse(restarted.state.value.a11yOffRequested)
        assertEquals(DeviceArm.DISARMED, restarted.state.value.arm)
        assertTrue(Settings.Secure.getString(app.contentResolver, key).contains("CompanionAccessibilityService"))
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(DeviceArm.DISARMED, restarted.state.value.arm)
    }

    @Test fun alreadyOffWithoutPermissionDoesNotNeedManualSettings() = runTest {
        val app = testApp()
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(app.contentResolver, key, other)
        val node = coordinator(app, this)
        assertTrue(node.turnAccessibilityOff())
        assertEquals(false, node.state.value.a11yListed)
        assertTrue(node.state.value.a11yOffRequested)
        assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
    }

    @Test fun deniedExplicitOffKeepsTruthfulStateAndManualOnNeverArms() = runTest {
        val app = testApp()
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val configured = "$other:${app.packageName}/${CompanionAccessibilityService::class.java.name}"
        Settings.Secure.putString(app.contentResolver, key, configured)
        val node = coordinator(app, this)
        assertFalse(node.turnAccessibilityOff())
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
        assertTrue(node.state.value.a11yOffRequested)
        assertEquals(true, node.state.value.a11yListed)
        assertEquals(configured, Settings.Secure.getString(app.contentResolver, key))
        assertFalse(node.turnAccessibilityOn())
        assertFalse(node.state.value.a11yOffRequested)
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
    }

    @Test fun lateConnectionCannotOverrideExplicitOff() = runTest {
        val app = testApp()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val node = coordinator(app, this)
        node.arm()
        runCurrent()
        node.turnAccessibilityOff()
        val service = Robolectric.buildService(CompanionAccessibilityService::class.java).create().get()
        try {
            connect(service)
            assertFalse(CompanionAccessibilityService.bound)
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(DeviceArm.DISARMED, node.state.value.arm)
            assertTrue(node.state.value.a11yOffRequested)
        } finally { service.onDestroy() }
    }

    @Test fun missingPermissionRetainsManualDirectOnlyFallback() = runTest {
        val app = testApp()
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(app.contentResolver, key, other)
        val node = coordinator(app, this)
        node.arm()
        runCurrent()
        assertEquals(DeviceArm.ARMED, node.state.value.arm)
        assertEquals(other, Settings.Secure.getString(app.contentResolver, key))
        node.disarm()
        assertEquals(DeviceArm.DISARMED, node.state.value.arm)
    }
}
