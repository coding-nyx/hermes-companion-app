package app.hermes.hands

import android.app.Application
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemotePairingTest {
    private val time = AtomicLong(1_000)
    private val issued = CopyOnWriteArrayList<String>()
    private val executor = Executors.newCachedThreadPool()
    private lateinit var pairing: RemotePairing

    @Before fun setup() {
        pairing = model()
    }

    @After fun cleanup() {
        pairing.close()
        executor.shutdownNow()
        assertTrue("Pairing worker leaked", executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun model(wait: Long = 1_000, issuer: ((String) -> Pair<ClientTokens.Client, String>)? = null) = RemotePairing(
        issuer ?: { name ->
            issued += name
            ClientTokens.Client("client-${issued.size}", name) to "unit-test-secret-${issued.size}"
        }, time::get, wait,
    )

    private fun body(code: String = pairing.state.value.code, name: String = "Test desktop") =
        JSONObject().put("code", code).put("client_name", name)

    private fun waiting(body: JSONObject = body(), peer: String = "192.0.2.10"): Future<JSONObject> =
        executor.submit(Callable { pairing.request(body, peer) }).also { awaitPending() }

    private fun awaitPending() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (pairing.state.value.pending == null && System.nanoTime() < deadline) Thread.sleep(1)
        assertNotNull("The request never became pending", pairing.state.value.pending)
    }

    private fun error(reply: JSONObject): String {
        assertFalse(reply.optBoolean("ok"))
        assertFalse("A failed pairing must not expose credentials", reply.has("result"))
        return reply.getJSONObject("error").getString("code")
    }

    private fun wrongCode(): String = if (pairing.state.value.code == "00000000") "11111111" else "00000000"

    @Test fun localWindowHasAnEightDigitCodeAndBoundedExpiry() {
        assertFalse(pairing.state.value.open)
        pairing.openWindow()
        assertTrue(pairing.state.value.open)
        assertTrue(pairing.state.value.code.matches(Regex("[0-9]{8}")))
        assertEquals(120L, pairing.state.value.expiresInSeconds)
        assertNull(pairing.state.value.pending)
        time.addAndGet(1_001)
        pairing.refresh()
        assertEquals(119L, pairing.state.value.expiresInSeconds)
        assertTrue(issued.isEmpty())
    }

    @Test fun correctCodeStillNeedsSeparateLocalApprovalAndDeliversOnlyToItsWorker() {
        pairing.openWindow()
        val future = waiting()
        assertTrue("Code must be consumed before local approval", pairing.state.value.code.isEmpty())
        assertTrue(issued.isEmpty())
        pairing.approve()
        val reply = future.get(1, TimeUnit.SECONDS)
        assertTrue(reply.getBoolean("ok"))
        val result = reply.getJSONObject("result")
        assertEquals("unit-test-secret-1", result.getString("token"))
        assertEquals("client-1", result.getString("client_id"))
        assertEquals("Test desktop", result.getString("label"))
        assertEquals(listOf("Test desktop"), issued.toList())
        assertFalse(pairing.state.value.open)
        assertNull(pairing.state.value.pending)
        assertFalse(pairing.state.value.toString().contains("unit-test-secret"))
        pairing.approve()
        assertEquals(1, issued.size)
    }

    @Test fun displaysTheActualPeerAndTrimmedLabelNotAnAddressFromTheBody() {
        pairing.openWindow()
        val future = waiting(body(name = "  My laptop  ").put("peer_address", "spoofed trusted machine"), "2001:db8::25")
        assertEquals(PendingPairing("My laptop", "2001:db8::25"), pairing.state.value.pending)
        pairing.approve()
        assertTrue(future.get(1, TimeUnit.SECONDS).getBoolean("ok"))
        assertEquals("My laptop", issued.single())
    }

    @Test fun codeCannotCreateOverlappingPendingRequestsOrBeReused() {
        pairing.openWindow()
        val code = pairing.state.value.code
        val first = waiting(body(code))
        assertEquals("pairing_pending", error(pairing.request(body(code, "Another desktop"), "192.0.2.11")))
        assertEquals("Test desktop", pairing.state.value.pending?.clientName)
        pairing.approve()
        assertTrue(first.get(1, TimeUnit.SECONDS).getBoolean("ok"))
        assertEquals("pairing_closed", error(pairing.request(body(code), "192.0.2.12")))
        assertEquals(1, issued.size)
    }

    @Test fun fiveWrongCodesCloseTheWindowAndDoNotIssue() {
        pairing.openWindow()
        val correct = pairing.state.value.code
        val wrong = wrongCode()
        repeat(5) { assertEquals("invalid_pairing_code", error(pairing.request(body(wrong), "192.0.2.10"))) }
        assertFalse(pairing.state.value.open)
        assertTrue(pairing.state.value.code.isEmpty())
        assertEquals("pairing_closed", error(pairing.request(body(correct), "192.0.2.10")))
        pairing.approve()
        assertTrue(issued.isEmpty())
    }

    @Test fun requestBudgetSurvivesReopenAndRecoversOnlyAfterItsMinute() {
        repeat(2) {
            pairing.openWindow()
            val wrong = wrongCode()
            repeat(5) { pairing.request(body(wrong), "192.0.2.10") }
            assertFalse(pairing.state.value.open)
        }
        pairing.openWindow()
        assertEquals("pairing_rate_limited", error(pairing.request(body(), "192.0.2.10")))
        assertTrue(issued.isEmpty())
        time.addAndGet(60_001)
        pairing.openWindow()
        val request = waiting()
        pairing.approve()
        assertTrue(request.get(1, TimeUnit.SECONDS).getBoolean("ok"))
        assertEquals(1, issued.size)
    }

    @Test fun closedWindowCannotIssueAndLocalDenialWakesItsWorker() {
        assertEquals("pairing_closed", error(pairing.request(body("12345678"), "192.0.2.10")))
        pairing.openWindow()
        val request = waiting()
        pairing.deny()
        assertEquals("pairing_denied", error(request.get(1, TimeUnit.SECONDS)))
        pairing.approve()
        assertTrue(issued.isEmpty())
    }

    @Test fun serverCloseAbortsPendingAndReopenDoesNotResurrectIt() {
        pairing.openWindow()
        val code = pairing.state.value.code
        val old = waiting()
        pairing.close()
        assertEquals("pairing_cancelled", error(old.get(1, TimeUnit.SECONDS)))
        pairing.openWindow()
        assertNotEquals("A replacement window must not repeat the immediately previous code", code, pairing.state.value.code)
        pairing.approve()
        assertTrue(issued.isEmpty())
        val replacement = waiting()
        pairing.approve()
        assertTrue(replacement.get(1, TimeUnit.SECONDS).getBoolean("ok"))
        assertEquals(1, issued.size)
    }

    @Test fun reopeningAnActivePendingWindowCancelsTheOldRequest() {
        pairing.openWindow()
        val old = waiting()
        pairing.openWindow()
        assertEquals("pairing_cancelled", error(old.get(1, TimeUnit.SECONDS)))
        assertNull(pairing.state.value.pending)
        assertTrue(pairing.state.value.open)
        pairing.approve()
        assertTrue(issued.isEmpty())
    }

    @Test fun windowExpiryClosesBeforeAnotherRequestCanBeApproved() {
        pairing.openWindow()
        val code = pairing.state.value.code
        time.addAndGet(120_000)
        pairing.refresh()
        assertFalse(pairing.state.value.open)
        assertEquals(0L, pairing.state.value.expiresInSeconds)
        assertEquals("pairing_closed", error(pairing.request(body(code), "192.0.2.10")))
        pairing.approve()
        assertTrue(issued.isEmpty())
    }

    @Test fun aPendingRequestCannotOutliveTheOriginalCodeWindow() {
        pairing = model(wait = 5_000)
        pairing.openWindow()
        time.addAndGet(119_500)
        val pending = waiting()
        assertEquals(1L, pairing.state.value.expiresInSeconds)
        time.addAndGet(500)
        pairing.refresh()
        assertEquals("pairing_expired", error(pending.get(1, TimeUnit.SECONDS)))
        pairing.approve()
        assertTrue(issued.isEmpty())
    }

    @Test fun boundedWaitTimeoutCannotBeApprovedLater() {
        pairing = model(wait = 25)
        pairing.openWindow()
        val pending = executor.submit(Callable { pairing.request(body(), "192.0.2.10") })
        assertEquals("pairing_timeout", error(pending.get(1, TimeUnit.SECONDS)))
        pairing.approve()
        assertFalse(pairing.state.value.open)
        assertTrue(issued.isEmpty())
    }

    @Test fun approvalQueuedAfterTheDeadlineCannotWinAgainstTimeout() {
        pairing.openWindow()
        val pending = waiting()
        time.addAndGet(1_000)
        val start = CountDownLatch(1)
        val approve = executor.submit { start.await(); pairing.approve() }
        val expire = executor.submit { start.await(); pairing.refresh() }
        start.countDown()
        approve.get(1, TimeUnit.SECONDS); expire.get(1, TimeUnit.SECONDS)
        assertEquals("pairing_timeout", error(pending.get(1, TimeUnit.SECONDS)))
        assertTrue(issued.isEmpty())
    }

    @Test fun competingLocalApprovalAndDenialCannotDoubleIssue() {
        pairing.openWindow()
        val pending = waiting()
        val start = CountDownLatch(1)
        val approve = executor.submit { start.await(); pairing.approve() }
        val deny = executor.submit { start.await(); pairing.deny() }
        start.countDown()
        approve.get(1, TimeUnit.SECONDS); deny.get(1, TimeUnit.SECONDS)
        val reply = pending.get(1, TimeUnit.SECONDS)
        if (reply.getBoolean("ok")) assertEquals(1, issued.size)
        else { assertEquals("pairing_denied", error(reply)); assertTrue(issued.isEmpty()) }
        pairing.approve()
        assertTrue(issued.size <= 1)
    }

    @Test fun controlCharactersAndMisleadingMultiLineLabelsAreRejected() {
        pairing.openWindow()
        for (name in listOf("Host\nApprove", "Host\u0000", "Host\u0085", "Host\u2028Approve", "Host\u202eApprove", "")) {
            assertEquals("bad_args", error(pairing.request(body(name = name), "192.0.2.10")))
            assertNull(pairing.state.value.pending)
        }
        assertTrue(issued.isEmpty())
    }

    @Test fun enforcesLabelBoundsAndAcceptsTheAlternativeLabelKey() {
        pairing.openWindow()
        assertEquals("bad_args", error(pairing.request(body(name = "x".repeat(81)), "192.0.2.10")))
        assertEquals("bad_args", error(pairing.request(body().put("client_name", 42), "192.0.2.10")))
        assertEquals("bad_args", error(pairing.request(body().put("label", "Different label"), "192.0.2.10")))
        val request = waiting(JSONObject().put("code", pairing.state.value.code).put("label", "x".repeat(80)))
        pairing.approve()
        assertTrue(request.get(1, TimeUnit.SECONDS).getBoolean("ok"))
        assertEquals(80, issued.single().length)
    }

    @Test fun labelLengthAppliesAfterTrimmingButControlsAreNeverTrimmedAway() {
        pairing.openWindow()
        assertEquals("bad_args", error(pairing.request(body(name = "\tHost"), "192.0.2.10")))
        val request = waiting(body(name = "  " + "x".repeat(80) + "  "))
        assertEquals(80, pairing.state.value.pending?.clientName?.length)
        pairing.approve()
        assertTrue(request.get(1, TimeUnit.SECONDS).getBoolean("ok"))
        assertEquals(80, issued.single().length)
    }

    @Test fun onlyAsciiEightDigitStringCodesAreAccepted() {
        pairing.openWindow()
        for (code in listOf<Any>("1234567", "123456789", "１２３４５６７８", "12 45678", 12345678)) {
            assertEquals("invalid_pairing_code", error(pairing.request(body().put("code", code), "192.0.2.10")))
        }
        assertFalse(pairing.state.value.open)
        assertTrue(issued.isEmpty())
    }

    @Test fun issuerFailureDoesNotCrashLocalApprovalOrLeakAResponse() {
        pairing = model(issuer = { throw IllegalStateException("private issuer failure details") })
        pairing.openWindow()
        val request = waiting()
        pairing.approve()
        val reply = request.get(1, TimeUnit.SECONDS)
        assertEquals("pairing_failed", error(reply))
        assertFalse(reply.toString().contains("private issuer failure"))
        assertFalse(pairing.state.value.open)
        assertNull(pairing.state.value.pending)
    }

    @Test fun interruptedWorkerCancelsPendingWithoutIssuing() {
        pairing.openWindow()
        val response = AtomicReference<JSONObject>()
        val request = body()
        val worker = Thread { response.set(pairing.request(request, "192.0.2.10")) }
        worker.start()
        try {
            awaitPending()
            worker.interrupt()
            worker.join(1_000)
            assertFalse("Interrupted pairing worker did not exit", worker.isAlive)
            assertEquals("pairing_cancelled", error(checkNotNull(response.get())))
            pairing.approve()
            assertTrue(issued.isEmpty())
        } finally { worker.interrupt(); worker.join(1_000) }
    }

    @Test fun waitIsCappedAtOneMinute() {
        for (wait in listOf(0L, -1L, 60_001L)) {
            try { model(wait); fail("Expected an invalid wait to be refused") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
}
