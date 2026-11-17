package app.hermes.hands

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in host-stdio bridge. Keep instrumentation running while the host pulls the private
 * token file and drives ONLY FIXTURE_PACKAGE. No bearer is printed to instrumentation output.
 */
@RunWith(AndroidJUnit4::class)
class HandsMcpAcceptanceBridgeTest : HandsAcceptanceSession() {
    override fun enabled(): Boolean = InstrumentationRegistry.getArguments().getString("hands_mcp_acceptance") == "true"

    @Test fun hostMcpCanDriveOnlyPreparedSyntheticScene() {
        val tokenFile = ownFile("acceptance-mcp-token.txt")
        val readyFile = ownFile("acceptance-mcp-ready.json")
        val doneFile = ownFile("acceptance-mcp-done.json")
        tokenFile.writeText(token)
        tokenFile.setReadable(false, false); tokenFile.setReadable(true, true)
        tokenFile.setWritable(false, false); tokenFile.setWritable(true, true)
        doneFile.delete()
        readyFile.writeText(JSONObject().put("ready", true).put("endpoint", "http://127.0.0.1:8765")
            .put("token_file", tokenFile.name).put("completion_file", doneFile.name)
            .put("fixture_package", FIXTURE_PACKAGE).put("controller_id", "acceptance-mcp")
            .put("primary_package", primaryPackage).toString())
        instrumentation.sendStatus(2, Bundle().apply {
            putString("hands_acceptance_ready", "files/${readyFile.name}")
            putString("hands_acceptance_fixture", FIXTURE_PACKAGE)
        })
        val seconds = InstrumentationRegistry.getArguments().getString("hands_mcp_timeout_seconds")?.toLongOrNull()?.coerceIn(30, 180) ?: 90
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000
        while (!doneFile.isFile && SystemClock.elapsedRealtime() < deadline) {
            assertPrimaryUnchanged()
            SystemClock.sleep(200)
        }
        assertTrue("The host did not signal MCP acceptance completion within the bounded window", doneFile.isFile)
        val completion = JSONObject(doneFile.readText())
        assertTrue("The host reported an unsuccessful MCP acceptance", completion.optBoolean("ok"))
        assertEquals("The host must name the synthetic target", FIXTURE_PACKAGE, completion.getString("fixture_package"))
        assertPrimaryUnchanged()
        // The host owns its holder cleanup, while this fixture independently revokes consent
        // and its one issued client in @After even when the host times out or fails.
    }
}
