package app.hermes.hands.engine

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Run debug APK via adb app_process as shell to test the SAME privileged implementation
 * before Shizuku is installed. Does not expose a listener or stay alive after the probe.
 */
object EngineProbe {
    @JvmStatic fun main(args: Array<String>) {
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        require(args.size == 1 && args[0].matches(Regex("[a-zA-Z][a-zA-Z0-9_.]+"))) { "Pass the installed debug package name" }
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("systemMain").invoke(null)
        val context = (threadClass.getMethod("getSystemContext").invoke(thread) as Context)
            .createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        val service = PrivilegedHandsService(context)
        val consume = HandlerThread("hands-probe-images").apply { start() }
        val images = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        images.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consume.looper))
        var id: Int? = null
        var exitCode = 0
        try {
            val before = service.foregroundPackage(0)
            val epoch = service.beginSession()
            fun execute(action: String, arguments: JSONObject = JSONObject(), display: Int = checkNotNull(id)): String =
                service.execute(action, arguments.put("_hands_session", epoch).toString(), display)
            id = service.createAgentScreen(images.surface, 720, 1280, 240)
            val launch = ProcessBuilder("/system/bin/am", "start", "--user", "0", "--display", id.toString(),
                "-f", "0x18000000", "-n", "${args[0]}/${AgentScreenFixtureActivity::class.java.name}").redirectErrorStream(true).start()
            val launchOutput = launch.inputStream.bufferedReader().readText()
            check(launch.waitFor() == 0 && !launchOutput.contains("Error:")) { launchOutput }
            Thread.sleep(1_500)
            val snapshot = JSONObject(execute("device.snapshot"))
            check(snapshot.getBoolean("ok")) { snapshot.toString() }
            val result = snapshot.getJSONObject("result")
            check(result.getString("app") == args[0]) { "The selected display is not showing the synthetic fixture" }
            check(listOf("top", "bottom", "left", "right").all { result.getJSONObject("safe_area").getInt(it) == 0 })
            val nodes = result.getJSONArray("nodes")
            check((0 until nodes.length()).any { nodes.getJSONObject(it).optString("text") == "ISOLATED AGENT FIXTURE" }) {
                "The synthetic fixture is not readable; unlock the phone before validation"
            }
            fun clickFixture(description: String): JSONObject {
                var previous: JSONObject? = null
                val differences = org.json.JSONArray()
                repeat(3) { attempt ->
                    val fresh = JSONObject(execute("device.snapshot"))
                    check(fresh.getBoolean("ok")) { fresh.toString() }
                    val view = fresh.getJSONObject("result")
                    check(view.getString("app") == args[0]) { "The display stopped showing the synthetic fixture" }
                    val freshNodes = view.getJSONArray("nodes")
                    check((0 until freshNodes.length()).any {
                        freshNodes.getJSONObject(it).optString("text") in setOf("ISOLATED AGENT FIXTURE", "ISOLATED TAP VERIFIED")
                    }) { "The synthetic fixture banner is no longer readable" }
                    val node = (0 until freshNodes.length()).asSequence().map { freshNodes.getJSONObject(it) }
                        .firstOrNull { it.optString("description") == description }
                        ?: error("Synthetic control missing: $freshNodes")
                    previous?.let { old ->
                        val changed = JSONObject()
                        (old.keys().asSequence().toSet() + node.keys().asSequence().toSet()).forEach { key ->
                            if (old.opt(key)?.toString() != node.opt(key)?.toString()) changed.put(key,
                                JSONObject().put("before", old.opt(key)).put("after", node.opt(key)))
                        }
                        differences.put(JSONObject().put("attempt", attempt + 1).put("fields", changed))
                    }
                    val response = JSONObject(execute("device.click", JSONObject().put("ref", node.getString("ref"))
                        .put("snapshot_id", view.getString("snapshot_id"))))
                    if (response.optBoolean("ok")) return response
                    // A stale refusal precedes input, so refresh/reselect is safe. Never repeat
                    // an input-submitted action or a timeout whose delivery is unknown.
                    check(response.optJSONObject("error")?.optString("code") == "stale_ref") { response.toString() }
                    previous = node
                    if (attempt < 2) Thread.sleep(200)
                }
                error("Synthetic reference stayed stale across 3 pre-input attempts: $differences")
            }
            clickFixture("hands-fixture-verify")
            val afterClick = JSONObject(execute("device.snapshot"))
            check(afterClick.toString().contains("ISOLATED TAP VERIFIED")) { afterClick.toString() }
            clickFixture("hands-fixture-input")
            val typed = JSONObject(execute("device.type", JSONObject().put("text", "agent fixture 42")))
            check(typed.getBoolean("ok")) { typed.toString() }
            val afterType = JSONObject(execute("device.snapshot"))
            check(afterType.toString().contains("agent fixture 42")) { afterType.toString() }
            val capture = JSONObject(execute("device.screenshot"))
            check(capture.getBoolean("ok")) { capture.toString() }
            val image = capture.getJSONObject("result")
            check(image.getJSONObject("screen_size").getInt("w") == 720 && image.getJSONObject("screen_size").getInt("h") == 1280)
            check(image.getJSONObject("size").getInt("w") == 720 && image.getJSONObject("size").getInt("h") == 1280)
            check(listOf("top", "bottom", "left", "right").all { image.getJSONObject("safe_area").getInt(it) == 0 })
            val bytes = android.util.Base64.decode(image.getString("png_b64"), android.util.Base64.DEFAULT)
            File("/data/local/tmp/hermes-hands-probe.png").writeBytes(bytes)
            val after = service.foregroundPackage(0)
            check(before == after) { "Primary app focus changed: $before -> $after" }
            val invalid = JSONObject(execute("device.click", JSONObject().put("x", 10).put("y", 10), id + 1000))
            check(!invalid.getBoolean("ok")) { "Invalid display unexpectedly accepted" }
            service.cancel()
            val stale = JSONObject(execute("device.click", JSONObject().put("x", 10).put("y", 10)))
            check(stale.getJSONObject("error").getString("code") == "disarmed") { "Canceled session accepted a new command" }
            println(JSONObject().put("ok", true).put("display_id", id).put("primary_before", before).put("primary_after", after)
                .put("tap_verified", true).put("text_verified", true).put("invalid_display_rejected", true)
                .put("canceled_session_rejected", true).put("screenshot_w", image.getInt("w"))
                .put("screenshot_h", image.getInt("h")).put("screenshot_sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
        } catch (error: Throwable) {
            exitCode = 1
            error.printStackTrace()
            println(JSONObject().put("ok", false).put("error", error.javaClass.name).put("message", error.message))
        } finally {
            runCatching { if (id != null) service.destroyAgentScreen() }
            images.close()
            consume.quitSafely()
            // app_process has a main looper and Shizuku's display handler; exit after cleanup.
            System.exit(exitCode)
        }
    }
}
