package app.hermes.hands

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.UUID

/** Real-device fixture lifecycle; never reads a primary-display tree, screenshot or user app. */
abstract class HandsAcceptanceSession {
    protected val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected lateinit var context: Context
    protected lateinit var runtime: HandsRuntime
    protected lateinit var client: ClientTokens.Client
    protected lateinit var token: String
    protected val controller = "acceptance-${UUID.randomUUID()}"
    protected var primaryPackage: String? = null
    private var originalBackend = "hybrid"
    private var originalTarget = "agent"
    private var originalClients = emptySet<String>()
    private var serviceStartedByTest = false
    private var configured = false
    private var ownedToken = false
    private val createdFiles = mutableListOf<File>()
    protected open fun enabled(): Boolean = true

    @Before fun prepareAuthorizedSyntheticSession() {
        assumeTrue("This fixture is opt-in", enabled())
        context = instrumentation.targetContext
        runtime = (context.applicationContext as HandsApplication).runtime
        assumeTrue("Agent-display acceptance requires Android 14 or newer", Build.VERSION.SDK_INT >= 34)
        assumeFalse("Unlock the phone before acceptance", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assumeTrue("Wake the phone before acceptance", context.getSystemService(PowerManager::class.java).isInteractive)
        assumeTrue("Grant Hands overlay access locally before acceptance", Settings.canDrawOverlays(context))
        assumeTrue("Grant Hands notification permission locally before acceptance", context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        assumeFalse("Stop the existing user session before acceptance", runtime.state.value.armed || runtime.state.value.consent)
        assumeFalse("The synthetic package is protected; review the local rule first", FIXTURE_PACKAGE in runtime.state.value.protectedPackages)
        runtime.shizuku.refresh()
        assumeTrue("Start and authorize Shizuku locally; this test never requests a permission", runtime.shizuku.status.value.running && runtime.shizuku.status.value.permissionGranted)

        originalBackend = runtime.state.value.backend
        originalTarget = runtime.state.value.target
        originalClients = runtime.tokens.clients().map { it.id }.toSet()
        // Bring only our own Activity forward to satisfy Android's foreground-service start rule.
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runtime.shizuku.connect() // Uses the existing grant; no permission request or dialog.
        }
        await("the existing Shizuku grant to bind", 12_000) { runtime.shizuku.status.value.bound }
        if (!runtime.state.value.serving) {
            serviceStartedByTest = true
            instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, Intent(context, HandsHostService::class.java)) }
        }
        await("Hands foreground service", 8_000) { runtime.state.value.serving && runtime.screenHost != null }
        instrumentation.runOnMainSync { runtime.configure("shizuku", "agent") }
        configured = true

        // The host may keep our own MainActivity available for safe, concurrent tab taps.
        // Otherwise HOME is the only primary navigation. Never attach Instrumentation's
        // UiAutomation: it would compete with the engine's display-specific connection.
        val primaryFixture = InstrumentationRegistry.getArguments().getString("hands_primary_fixture") == "true"
        val expectedPrimary = if (primaryFixture) context.packageName else {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val launcher = context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
            assumeTrue("A default home launcher is required", !launcher.isNullOrBlank())
            instrumentation.runOnMainSync { context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            launcher
        }
        await("the selected primary fixture", 8_000) { runtime.shizuku.foregroundPackage(0) == expectedPrimary }
        primaryPackage = expectedPrimary
        instrumentation.runOnMainSync { runtime.approve() }
        val issued = runtime.tokens.issue("Synthetic Hands acceptance")
        client = issued.first; token = issued.second; ownedToken = true
        val status = getStatus()
        assertEquals("hermes-hands", status.getString("service"))
        assertFalse(status.getBoolean("armed"))
        assertTrue(status.getBoolean("consent_approved"))
        assertPrimaryUnchanged()
    }

    @After fun cleanOnlyOwnedAcceptanceResources() {
        if (!::runtime.isInitialized) return
        try {
            if (configured) {
                val ownedDisplay = runtime.state.value.displayId
                instrumentation.runOnMainSync { runtime.disarm() }
                await("the owned agent display to close", 8_000) {
                    runtime.state.value.displayId <= 0 &&
                        (ownedDisplay <= 0 || context.getSystemService(DisplayManager::class.java).getDisplay(ownedDisplay) == null)
                }
            }
        } finally {
            if (ownedToken) {
                runtime.tokens.revoke(client.id)
                assertNull("The synthetic token must be revoked", runtime.tokens.authenticate(token))
                assertTrue("Acceptance must preserve pre-existing clients", runtime.tokens.clients().map { it.id }.containsAll(originalClients))
            }
            createdFiles.forEach { it.delete() }
            if (configured) instrumentation.runOnMainSync { runtime.configure(originalBackend, originalTarget) }
            if (serviceStartedByTest) {
                instrumentation.runOnMainSync { context.stopService(Intent(context, HandsHostService::class.java)) }
                await("the test-started service to stop", 8_000) { !runtime.state.value.serving }
            }
        }
    }

    protected fun arm(): String = requireOk(command("device.arm", JSONObject().put("duration_seconds", 300)))
        .getString("lease_id")

    protected fun openSyntheticScene(holder: String): Int {
        val display = requireOk(command("device.agent_screen.start", JSONObject().put("width", 720).put("height", 1280).put("density", 240), holder))
            .getInt("display_id")
        assertTrue("Agent display must be independent of display zero", display > 0)
        requireOk(command("device.open_app", JSONObject().put("package", FIXTURE_PACKAGE), holder))
        snapshotUntil(holder) { it.getString("app") == FIXTURE_PACKAGE && containsText(it, "HANDS ACCEPTANCE FIXTURE") }
        assertPrimaryUnchanged()
        return display
    }

    protected fun snapshotUntil(holder: String, predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        var lastCode = "not_observed"
        do {
            val reply = command("device.snapshot", holder = holder)
            if (reply.optBoolean("ok")) {
                val snapshot = reply.getJSONObject("result")
                assertEquals("Only the synthetic APK may be observed", FIXTURE_PACKAGE, snapshot.getString("app"))
                if (predicate(snapshot)) return snapshot
            } else {
                lastCode = reply.optJSONObject("error")?.optString("code").orEmpty()
                check(lastCode in setOf("observation_unavailable", "screen_unstable", "screen_unavailable", "controller_busy")) { "Snapshot refused: $lastCode" }
            }
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Synthetic scene did not settle ($lastCode)")
    }

    protected fun clickRef(holder: String, label: String): JSONObject {
        repeat(3) {
            val snapshot = snapshotUntil(holder) { findNode(it, label) != null }
            val node = checkNotNull(findNode(snapshot, label))
            val reply = command("device.click", JSONObject().put("ref", node.getString("ref"))
                .put("snapshot_id", snapshot.getString("snapshot_id")), holder)
            if (reply.optBoolean("ok")) return reply.getJSONObject("result")
            // Only a documented pre-input stale-ref refusal is safe to retry.
            if (reply.optJSONObject("error")?.optString("code") != "stale_ref") return requireOk(reply)
        }
        throw AssertionError("The synthetic control did not remain stable")
    }

    protected fun verifyAndSavePng(shot: JSONObject, display: Int): File {
        assertEquals(display, shot.getInt("display_id"))
        assertEquals("display", shot.getString("coordinate_space"))
        assertEquals("image/png", shot.getString("mime"))
        val size = shot.getJSONObject("screen_size")
        assertEquals(720, size.getInt("w")); assertEquals(1280, size.getInt("h"))
        assertEquals("[0,0,720,1280]", shot.getJSONArray("window_bounds").toString())
        val bytes = Base64.decode(shot.getString("png_b64"), Base64.DEFAULT)
        assertTrue(bytes.size > 100)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), bytes.copyOfRange(0, 8))
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Android could not decode the PNG" }
        try {
            assertEquals(shot.getInt("w"), bitmap.width); assertEquals(shot.getInt("h"), bitmap.height)
            assertTrue(bitmap.width in 1..1080 && bitmap.height in 1..1080)
        } finally { bitmap.recycle() }
        // This image contains only the synthetic test APK. Retain it for host visual review.
        return File(context.getExternalFilesDir("acceptance"), "synthetic-agent-display.png").also { it.writeBytes(bytes) }
    }

    protected fun getStatus(): JSONObject = requireOk(http("GET", "/v1/status"))
    protected fun command(action: String, arguments: JSONObject = JSONObject(), holder: String? = null,
        controllerId: String = controller): JSONObject {
        val request = JSONObject().put("action", action).put("arguments", arguments).put("controller_id", controllerId)
        holder?.let { request.put("lease_id", it) }
        return http("POST", "/v1/commands", request).also { assertPrimaryUnchanged() }
    }

    protected fun requireOk(reply: JSONObject): JSONObject {
        assertTrue("Hands refused the synthetic action: ${reply.optJSONObject("error")?.optString("code")}", reply.optBoolean("ok"))
        return reply.getJSONObject("result")
    }

    protected fun assertPrimaryUnchanged() {
        val expected = primaryPackage ?: return
        assertEquals("An agent action changed the primary task focus", expected, runtime.shizuku.foregroundPackage(0))
    }

    protected fun await(label: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (predicate()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $label")
    }

    protected fun ownFile(name: String): File = File(context.filesDir, name).also { createdFiles += it }

    private fun http(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = URL("http://127.0.0.1:8765$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            assertEquals("The authenticated local HTTP request was rejected", 200, status)
            return JSONObject(raw)
        } finally { connection.disconnect() }
    }

    companion object {
        const val FIXTURE_PACKAGE = "app.hermes.hands.test"
        fun findNode(snapshot: JSONObject, label: String): JSONObject? = snapshot.getJSONArray("nodes").let { nodes ->
            (0 until nodes.length()).asSequence().map { nodes.getJSONObject(it) }.firstOrNull {
                it.optString("text") == label || it.optString("description") == label
            }
        }
        fun containsText(snapshot: JSONObject, text: String): Boolean = findNode(snapshot, text) != null
    }
}
