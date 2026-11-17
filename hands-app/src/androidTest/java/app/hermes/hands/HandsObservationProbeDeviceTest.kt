package app.hermes.hands

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic-only diagnostic proving exact refs stay usable across independent reads. */
@RunWith(AndroidJUnit4::class)
class HandsObservationProbeDeviceTest : HandsAcceptanceSession() {
    @Test fun independentlyReadSyntheticFieldRemainsTheSameExactControl() {
        val holder = arm()
        openSyntheticScene(holder)
        val snapshots = (0..2).map { index ->
            snapshotUntil(holder) { findNode(it, "Agent test input") != null }.also {
                ownFile("synthetic-observation-$index.json").writeText(it.toString())
            }
        }
        val latest = snapshots.last()
        val reply = command("device.click", JSONObject().put("ref", checkNotNull(findNode(latest, "Agent test input")).getString("ref"))
            .put("snapshot_id", latest.getString("snapshot_id")), holder)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("synthetic_observation_windows", snapshots.joinToString { it.getInt("window_id").toString() })
            putString("synthetic_click_result", reply.toString())
        })
        // Fixtures are deleted by @After; expose only node identity fields for diagnostics.
        snapshots.forEachIndexed { i, view -> instrumentation.sendStatus(2, Bundle().apply {
            putString("synthetic_input_$i", findNode(view, "Agent test input").toString())
        }) }
        assertTrue(reply.toString(), reply.optBoolean("ok"))
        assertPrimaryUnchanged()
    }
}
