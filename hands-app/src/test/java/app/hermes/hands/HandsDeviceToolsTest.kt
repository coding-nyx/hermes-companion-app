package app.hermes.hands

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.device.DevicePrivacy
import app.hermes.companion.device.FileToolbox
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HandsDeviceToolsTest {
    @get:Rule val temp = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun consentAndPermissions() {
        DevicePrivacy.initialize(app)
        DevicePrivacy.releaseLocally(app)
        shadowOf(app).denyPermissions(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CALL_PHONE,
            Manifest.permission.WRITE_SECURE_SETTINGS)
        android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        CompanionAccessibilityService.setExplicitlyOff(app, false)
    }

    private fun tools() = HandsDeviceTools(app, { setOf("protected.app.*") }) { permit, protected ->
        FileToolbox(app, permit, protected, roots = { listOf(temp.root) }, granted = { true })
    }
    private fun execute(action: String, args: JSONObject = JSONObject(), permit: () -> Unit = {}): JSONObject =
        checkNotNull(tools().execute(action, args, permit))

    @Test fun ownsExactlyTheMigratedActionsAndPersonalReadsAreNotMetadata() {
        assertEquals(37, HandsDeviceTools.ACTIONS.size)
        assertEquals(setOf("device.files.access", "device.voice.status", "device.audio.dnd", "device.accessibility.status"), HandsDeviceTools.META_ACTIONS)
        listOf("device.sms.list", "device.calls.log", "device.contacts.search", "device.calendar.list", "device.location.last", "device.apps.usage",
            "device.files", "device.files.enable", "device.sms.send", "device.calls.place").forEach {
            assertTrue(it, it in HandsDeviceTools.ACTIONS)
            assertFalse(it, it in HandsDeviceTools.META_ACTIONS)
        }
        var checked = false
        assertNull(tools().execute("device.notifications.reply", JSONObject()) { checked = true })
        assertFalse(checked)
    }

    @Test fun personalProviderDenialsNeverBecomeSuccessfulEmptyLists() {
        listOf("device.sms.list", "device.calls.log", "device.contacts.search", "device.calendar.list", "device.location.last").forEach { action ->
            val reply = execute(action)
            assertFalse(action, reply.getBoolean("ok"))
            assertEquals(action, "permission_denied", reply.getJSONObject("error").getString("code"))
        }
        val send = execute("device.sms.send", JSONObject().put("address", "+15550000000").put("body", "synthetic only"))
        assertEquals("permission_denied", send.getJSONObject("error").getString("code"))
        assertFalse(send.toString().contains("synthetic only"))
        val call = execute("device.calls.place", JSONObject().put("address", "+15550000000"))
        assertEquals("permission_denied", call.getJSONObject("error").getString("code"))
    }

    @Test fun smsProviderShapeAndTimestampPrecisionArePreserved() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        val provider = registerSms()
        var checks = 0
        val reply = execute("device.sms.list", JSONObject().put("since_ms", 1759230123456L).put("limit", 1)) { checks++ }
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        val result = reply.getJSONObject("result")
        assertEquals(1, result.getInt("count"))
        val message = result.getJSONArray("messages").getJSONObject(0)
        assertEquals("synthetic only", message.getString("body"))
        assertEquals("inbox", message.getString("type"))
        assertEquals(1759230123456L, message.getLong("date_ms"))
        assertEquals(listOf("1759230123456"), provider.queryArgs?.toList())
        assertTrue(checks >= 2)
    }

    @Test fun consentBeforeQueryAndRevocationBeforePublicationAreBothEnforced() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        val provider = registerSms()
        assertThrows(IllegalStateException::class.java) { execute("device.sms.list") { error("disarmed") } }
        assertEquals(0, provider.queries)
        var allowed = true
        provider.afterQuery = { allowed = false }
        val failure = assertThrows(IllegalStateException::class.java) {
            execute("device.sms.list") { check(allowed) { "disarmed" } }
        }
        assertEquals("disarmed", failure.message)
        assertEquals(1, provider.queries)
    }

    @Test fun fileOperationsRetainChunkChecksAndNeverPublishACancelledStage() {
        File(temp.root, "source").writeBytes(ByteArray(1024 * 1024))
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            execute("device.files", JSONObject().put("action", "copy").put("path", "source").put("destination", "copy")) {
                check(++checks < 6) { "disarmed" }
            }
        }
        assertEquals(listOf("source"), temp.root.listFiles()!!.map { it.name })
        assertTrue(checks >= 6)
    }

    @Test fun filesProtectOwnAppAndCustomPackagesAndPreserveTransactionalHashes() {
        val own = File(temp.root, "Android/media/${app.packageName}/private").apply { parentFile?.mkdirs(); writeText("keep") }
        val rejected = execute("device.files", JSONObject().put("action", "read").put("path", own.path))
        assertEquals("protected_package", rejected.getJSONObject("error").getString("code"))
        val write = execute("device.files", JSONObject().put("action", "write").put("path", "note").put("content", "fixture नमस्ते"))
        assertTrue(write.toString(), write.getBoolean("ok"))
        val hash = execute("device.files", JSONObject().put("action", "hash").put("path", "note"))
        assertTrue(hash.getJSONObject("result").getString("sha256").matches(Regex("[0-9a-f]{64}")))
        val read = execute("device.files", JSONObject().put("action", "read").put("path", "note"))
        assertEquals("fixture नमस्ते", read.getJSONObject("result").getString("content"))
    }

    @Test fun handoffBooleanArgumentsAndPhoneSettingsRemainLocalIntents() {
        val alarm = execute("device.alarm.set", JSONObject().put("hour", 7).put("minute", 15).put("skip_ui", false))
        assertTrue(alarm.toString(), alarm.getBoolean("ok"))
        assertFalse(alarm.getJSONObject("result").getBoolean("skip_ui_requested"))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(android.provider.AlarmClock.ACTION_SET_ALARM, intent.action)
        assertFalse(intent.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true))
        val enabled = execute("device.accessibility.enable")
        assertTrue(enabled.getJSONObject("result").getBoolean("settings_opened"))
        assertFalse(enabled.getJSONObject("result").getBoolean("permission_granted_automatically"))
        assertEquals(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun rejectsSelfUninstallAndMissingVoiceOrSmsArgumentsBeforeSideEffects() {
        val uninstall = execute("device.apps.uninstall", JSONObject().put("package", app.packageName))
        assertEquals("protected_package", uninstall.getJSONObject("error").getString("code"))
        assertEquals("missing_args", execute("device.voice.speak").getJSONObject("error").getString("code"))
        assertEquals("missing_args", execute("device.sms.send").getJSONObject("error").getString("code"))
    }

    @Test fun secureGrantEnablesOnlyHandsAndClearsExplicitOffWithoutOpeningPhoneSettings() {
        val other = "org.reader/.ReaderService"
        android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, other)
        CompanionAccessibilityService.setExplicitlyOff(app, true)
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val reply = checkNotNull(HandsDeviceTools(app, { emptySet() }, targetIsAgent = { true })
            .execute("device.accessibility.enable", JSONObject()) {})
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        val result = reply.getJSONObject("result")
        assertTrue(result.getBoolean("enabled_automatically"))
        assertTrue(result.getBoolean("can_write_secure_settings"))
        assertFalse(result.getBoolean("explicitly_off"))
        assertFalse(result.getBoolean("settings_opened"))
        assertNull(shadowOf(app).nextStartedActivity)
        val listed = android.provider.Settings.Secure.getString(app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        assertTrue(listed.contains(other))
        assertTrue(listed.contains(app.packageName))
    }

    @Test fun cancelledSecureEnableRestoresExplicitOffAndRemovesOnlyHands() {
        val other = "org.reader/.ReaderService"
        android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, other)
        CompanionAccessibilityService.setExplicitlyOff(app, true)
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            tools().execute("device.accessibility.enable", JSONObject()) {
                if (++checks >= 5) throw IllegalStateException("disarmed")
            }
        }
        assertTrue(CompanionAccessibilityService.explicitlyOff(app))
        assertEquals(other, android.provider.Settings.Secure.getString(app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun explicitEnableRepairsAlreadyListedUnboundAccessibility() {
        val other = "org.reader/.ReaderService"
        val component = android.content.ComponentName(app, CompanionAccessibilityService::class.java).flattenToString()
        android.provider.Settings.Secure.putString(app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "$other:$component")
        CompanionAccessibilityService.setExplicitlyOff(app, false)
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertFalse(CompanionAccessibilityService.bound)
        val reply = checkNotNull(tools().execute("device.accessibility.enable", JSONObject()) {})
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        assertTrue(reply.getJSONObject("result").getBoolean("enabled_automatically"))
        assertEquals("$other:$component", android.provider.Settings.Secure.getString(app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(1, android.provider.Settings.Secure.getInt(app.contentResolver,
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun agentTargetNeverLaunchesPhysicalActivitiesWithoutAnExplicitHandoff() {
        val tools = HandsDeviceTools(app, { emptySet() }, targetIsAgent = { true })
        for (action in HandsDeviceTools.ACTIVITY_HANDOFF_ACTIONS) {
            val reply = checkNotNull(tools.execute(action, JSONObject()) {})
            assertEquals(action, "primary_handoff_required", reply.getJSONObject("error").getString("code"))
        }
        assertNull(shadowOf(app).nextStartedActivity)
        val allowed = checkNotNull(tools.execute("device.calls.dial", JSONObject().put("address", "+15550000000")
            .put("allow_primary_handoff", true)) {})
        assertTrue(allowed.toString(), allowed.getBoolean("ok"))
        assertEquals("primary", allowed.getJSONObject("result").getString("handoff_target"))
        assertEquals(Intent.ACTION_DIAL, shadowOf(app).nextStartedActivity.action)
    }

    private fun registerSms(): SyntheticSmsProvider = SyntheticSmsProvider().also {
        it.attachInfo(app, ProviderInfo().apply { authority = "sms"; exported = true })
        ShadowContentResolver.registerProviderInternal("sms", it)
    }

    class SyntheticSmsProvider : ContentProvider() {
        var queries = 0
        var queryArgs: Array<out String>? = null
        var afterQuery: (() -> Unit)? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            queryArgs = selectionArgs
            val columns = checkNotNull(projection)
            val cursor = MatrixCursor(columns)
            cursor.addRow(columns.map { column -> when (column) {
                Telephony.Sms._ID -> 1L
                Telephony.Sms.ADDRESS -> "+15550000000"
                Telephony.Sms.BODY -> "synthetic only"
                Telephony.Sms.DATE -> 1759230123456L
                Telephony.Sms.TYPE -> Telephony.Sms.MESSAGE_TYPE_INBOX
                Telephony.Sms.READ -> 0
                Telephony.Sms.THREAD_ID -> 2L
                else -> null
            } })
            afterQuery?.invoke()
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
