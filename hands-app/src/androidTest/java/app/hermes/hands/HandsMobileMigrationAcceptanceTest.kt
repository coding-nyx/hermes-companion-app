package app.hermes.hands

import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermes.companion.device.FileToolbox
import app.hermes.companion.device.NotifStreamEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime/HTTP migration proof using only generated files and injected synthetic events. */
@RunWith(AndroidJUnit4::class)
class HandsMobileMigrationAcceptanceTest : HandsAcceptanceSession() {
    @Test fun sharedStoragePushHashAndPullRoundTripPreservesBytesAndCleansItsOwnDirectory() {
        assumeTrue("Grant Hands shared-file access locally first; the test never grants it", Environment.isExternalStorageManager())
        val holder = arm()
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hands-acceptance-${UUID.randomUUID()}")
        val target = File(directory, "generated-binary.dat")
        val generated = ByteArray(FileToolbox.MAX_CHUNK + 137) { (it * 31).toByte() }
        assertFalse("Never reuse a user-owned directory", directory.exists())
        var created = false
        try {
            file(holder, "mkdir", directory).also { created = true }
            file(holder, "write", target, JSONObject().put("data_base64", Base64.encodeToString(generated.copyOfRange(0, FileToolbox.MAX_CHUNK), Base64.NO_WRAP)))
            file(holder, "append", target, JSONObject().put("offset", FileToolbox.MAX_CHUNK).put("data_base64",
                Base64.encodeToString(generated.copyOfRange(FileToolbox.MAX_CHUNK, generated.size), Base64.NO_WRAP)))
            val hashed = file(holder, "hash", target)
            assertEquals(generated.size.toLong(), hashed.getLong("size"))
            assertEquals(sha(generated), hashed.getString("sha256"))
            val pulled = ByteArrayOutputStream()
            var offset = 0
            var version: String? = null
            do {
                val args = JSONObject().put("offset", offset).put("max_bytes", FileToolbox.MAX_CHUNK).put("encoding", "base64")
                version?.let { args.put("version", it) }
                val chunk = file(holder, "read", target, args)
                if (version == null) version = chunk.getString("version")
                assertEquals(version, chunk.getString("version"))
                val bytes = Base64.decode(chunk.getString("data_base64"), Base64.DEFAULT)
                assertEquals(chunk.getInt("bytes"), bytes.size)
                pulled.write(bytes); offset += bytes.size
            } while (!chunk.getBoolean("eof"))
            assertArrayEquals(generated, pulled.toByteArray())
            assertPrimaryUnchanged()
        } finally {
            // Exact UUID directory only. Never list/read unrelated Download entries.
            if (created) {
                val removed = command("device.files", JSONObject().put("action", "delete").put("path", directory.path).put("recursive", true), holder)
                if (!removed.optBoolean("ok")) assertTrue("Clean only the generated fixture directory", directory.deleteRecursively())
                assertFalse(directory.exists())
            }
        }
    }

    @Test fun deniedPersonalDataToolsNeverReadProvidersOrSendMessages() {
        val denied = listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
        assumeTrue("Skip when any personal-data grant exists; this test never reads a granted provider or sends SMS",
            denied.all { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED })
        val holder = arm()
        listOf("device.sms.list", "device.calls.log", "device.contacts.search").forEach { action ->
            assertEquals(action, "permission_denied", failure(command(action, holder = holder)))
        }
        val send = command("device.sms.send", JSONObject().put("address", "+15550000000").put("body", "Synthetic denied fixture only"), holder)
        assertEquals("permission_denied", failure(send))
        assertFalse(send.toString().contains("Synthetic denied fixture only"))
        assertPrimaryUnchanged()
    }

    @Test fun syntheticNotificationConsentNarrowingClearScopedReleaseAndGlobalStopUseTheRuntime() {
        val model = runtime.notifications
        val original = model.state.value
        assumeFalse("Do not interrupt an existing passive user subscription", original.enabled)
        assumeFalse("The synthetic package is protected", FIXTURE_PACKAGE in runtime.state.value.protectedPackages)
        val other = runtime.tokens.issue("Synthetic notification outsider")
        try {
            // Instrumentation supplies the same explicit local selection as the phone form.
            // The only selected package is the empty synthetic APK; no real notification is posted.
            model.configureLocal(true, client.id, setOf(FIXTURE_PACKAGE), "full", 2, false)
            val key = UUID.randomUUID().toString().replace("-", "")
            fun inject() {
                val now = System.currentTimeMillis()
                model.accept(NotifStreamEvent(key, FIXTURE_PACKAGE, "Synthetic title", "Synthetic body", "msg",
                    false, false, now, model.state.value.consentVersion, now, model.state.value.content))
            }
            fun events() = requireOk(command("device.notifications.list")).getJSONArray("events")
            inject()
            assertEquals(1, events().length())
            val denied = runtime.command(other.first, JSONObject().put("action", "device.notifications.list").put("arguments", JSONObject())
                .put("controller_id", "synthetic-outsider"))
            assertFalse(denied.optBoolean("ok"))
            assertFalse(denied.toString().contains("Synthetic body"))

            val holder = arm()
            val beforeClear = model.state.value.consentVersion
            val cleared = requireOk(command("device.notifications.clear", holder = holder))
            assertTrue(cleared.getBoolean("cleared"))
            assertTrue(cleared.getLong("consent_version") > beforeClear)
            assertEquals(0, events().length())
            val narrowed = requireOk(command("device.notifications.stream.set", JSONObject().put("content", "title"), holder))
            assertEquals("title", narrowed.getString("content"))
            inject()
            val passive = events().getJSONObject(0).getJSONObject("notification")
            assertEquals("Synthetic title", passive.getString("title"))
            assertEquals("", passive.getString("text"))
            assertTrue(passive.getBoolean("passive"))
            requireOk(command("device.disarm", holder = holder))
            assertFalse(runtime.state.value.armed)
            assertTrue("Holder release must preserve separately approved passive sharing", model.state.value.enabled)
            assertEquals(1, events().length())

            instrumentation.runOnMainSync { runtime.approve() }
            arm()
            runtime.stopSession() // Same path used when a control backend/overlay fails.
            assertFalse(runtime.state.value.armed)
            assertTrue("A control-backend failure must preserve independent passive approval", model.state.value.enabled)
            assertEquals(1, events().length())

            requireOk(command("device.disarm"))
            assertFalse("Explicit global Stop withdraws passive sharing", model.state.value.enabled)
            assertEquals(0, model.state.value.queued)
            assertFalse(command("device.notifications.list").optBoolean("ok"))
            assertPrimaryUnchanged()
        } finally {
            model.configureLocal(false, original.clientId, original.packages, original.content, original.retentionMinutes, original.includeCalls)
            runtime.tokens.revoke(other.first.id)
        }
    }
    private fun file(holder: String, operation: String, path: File, extra: JSONObject = JSONObject()): JSONObject =
        requireOk(command("device.files", extra.put("action", operation).put("path", path.path), holder))
    private fun failure(reply: JSONObject): String {
        assertFalse(reply.toString(), reply.optBoolean("ok")); return reply.getJSONObject("error").getString("code")
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
