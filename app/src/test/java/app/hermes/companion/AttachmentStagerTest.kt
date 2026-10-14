package app.hermes.companion

import android.app.Application
import android.net.Uri
import app.hermes.companion.model.ChatBlockKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class AttachmentStagerTest {
    @Test fun successfulPreparationKeepsTheDraftAndOwnsTheStagedFile() = runTest {
        val bytes = "fixture".toByteArray()
        val f = Fixture(this) { _, _ -> bytes }
        try {
            f.stage(); assertTrue(f.stager.isPreparingCurrent()); runCurrent()
            assertFalse(f.stager.isPreparingCurrent())
            assertEquals("typed draft", f.state.value.draft)
            val item = f.state.value.pendingAttachments.single()
            assertEquals("fixture", File(item.localUri).readText())
            assertEquals(ChatBlockKind.FILE, item.kind)
            assertTrue(bytes.all { it == 0.toByte() })
        } finally { f.close() }
    }

    @Test fun hostProfileAndCloseReopenChangesRejectLateFilesAndErrors() = runTest {
        for (change in 0..2) {
            val result = CompletableDeferred<ByteArray>()
            val bytes = byteArrayOf(5, 6)
            val f = Fixture(this) { _, _ -> result.await() }
            try {
                f.stage(); runCurrent()
                when (change) {
                    0 -> f.state.value = f.state.value.copy(origin = "http://other", error = "new screen")
                    1 -> f.state.value = f.state.value.copy(activeProfileId = "other", error = "new screen")
                    else -> { f.epoch++; f.state.value = f.state.value.copy(error = "new screen") }
                }
                assertFalse(f.stager.isPreparingCurrent())
                result.complete(bytes); runCurrent()
                assertTrue(f.state.value.pendingAttachments.isEmpty())
                assertEquals("new screen", f.state.value.error)
                assertTrue(f.directory.listFiles().orEmpty().isEmpty())
                assertTrue(bytes.all { it == 0.toByte() })
            } finally { f.close() }
        }
    }

    @Test fun anOldFileFailureCannotReplaceTheNewConversationsError() = runTest {
        val result = CompletableDeferred<ByteArray>()
        val f = Fixture(this) { _, _ -> result.await() }
        try {
            f.stage(); runCurrent(); f.epoch++
            f.state.value = f.state.value.copy(error = "new conversation")
            result.completeExceptionally(SecurityException("revoked")); runCurrent()
            assertEquals("new conversation", f.state.value.error)
            assertTrue(f.state.value.pendingAttachments.isEmpty())
        } finally { f.close() }
    }

    @Test fun draftPromotionRetainsTheAttachmentWithinTheSameConversation() = runTest {
        val result = CompletableDeferred<ByteArray>()
        val f = Fixture(this) { _, _ -> result.await() }
        try {
            f.state.value = f.state.value.copy(openSessionId = "local:draft")
            f.stage(); runCurrent()
            f.state.value = f.state.value.copy(openSessionId = "persisted") // No navigation epoch change.
            result.complete(byteArrayOf(1)); runCurrent()
            assertEquals(1, f.state.value.pendingAttachments.size)
        } finally { f.close() }
    }

    @Test fun aLargeInputStopsAtTheLimitBeforeCreatingAnyCacheFile() = runTest {
        var read = 0
        val input = object : InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(1, offset, offset + length); read += length; return length
            }
        }
        val f = Fixture(this) { _, limit -> AttachmentStager.readLimited(input, limit) }
        try {
            f.stage(); runCurrent()
            assertEquals(11, read)
            assertTrue(f.state.value.error.orEmpty().contains("too large"))
            assertTrue(f.directory.listFiles().orEmpty().isEmpty())
            assertEquals("typed draft", f.state.value.draft)
        } finally { f.close() }
    }

    @Test fun exactLimitFilesAreAcceptedWithoutTruncation() = runTest {
        val bytes = AttachmentStager.readLimited(ByteArrayInputStream(ByteArray(10) { 3 }), 10)
        assertEquals(10, bytes.size); assertTrue(bytes.all { it == 3.toByte() })
    }

    @Test fun roomAttachmentsAreExplicitlyUnsupportedAndNeverRead() = runTest {
        var reads = 0
        val f = Fixture(this) { _, _ -> reads++; byteArrayOf(1) }
        try {
            f.state.value = f.state.value.copy(openRoomId = "room", openSessionId = null)
            f.stage(); runCurrent()
            assertEquals(0, reads); assertTrue(f.state.value.error.orEmpty().contains("rooms"))
            assertFalse(f.stager.isPreparingCurrent())
        } finally { f.close() }
    }

    @Test fun queuedPreparationIsSerializedAndCancellationRetiresAdmission() = runTest {
        val first = CompletableDeferred<ByteArray>(); var reads = 0
        val f = Fixture(this) { _, _ -> reads++; if (reads == 1) first.await() else byteArrayOf(2) }
        try {
            f.stage(); f.stage(); runCurrent()
            assertEquals(1, reads)
            first.complete(byteArrayOf(1)); runCurrent()
            assertEquals(2, f.state.value.pendingAttachments.size)
            assertFalse(f.stager.isPreparingCurrent())
            f.scope.cancel(); f.stage(); runCurrent()
            assertFalse(f.stager.isPreparingCurrent())
        } finally { f.close() }
    }

    private class Fixture(test: TestScope, reader: suspend (Uri, Int) -> ByteArray) {
        val state = MutableStateFlow(CompanionState(origin = "http://lab", activeProfileId = "test",
            openSessionId = "thread", draft = "typed draft"))
        var epoch = 1L
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "stage-${UUID.randomUUID()}")
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val stager = AttachmentStager(RuntimeEnvironment.getApplication(), state, scope, { epoch },
            io = dispatcher, main = dispatcher, directory = directory, maximum = { 10 }, read = reader)
        fun stage() = stager.stage(Uri.parse("content://synthetic/file"), "text/plain", "fixture.txt")
        fun close() { scope.cancel(); directory.deleteRecursively() }
    }
}
