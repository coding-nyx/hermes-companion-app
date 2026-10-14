package app.hermes.companion

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production staging on the phone with synthetic state/files. No picker, host request or microphone. */
@RunWith(AndroidJUnit4::class)
class AttachmentStagerDeviceTest {
    @Test fun aLargeSyntheticImageStagesAsAValidJpegWithThePreservedAspectRatio() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        val image = Bitmap.createBitmap(3840, 1920, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, png); image.recycle()
        val source = File(i.targetContext.cacheDir, "stage-source-${UUID.randomUUID()}.png").apply { writeBytes(png.toByteArray()) }
        val directory = File(i.targetContext.cacheDir, "stage-fixture-${UUID.randomUUID()}")
        val state = MutableStateFlow(CompanionState(origin = "http://synthetic", activeProfileId = "synthetic",
            openSessionId = "synthetic-thread", draft = "image draft"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val stager = AttachmentStager(i.targetContext, state, scope, { 1 }, directory = directory)
        try {
            i.runOnMainSync { stager.stage(Uri.fromFile(source), "image/png", "fixture.png") }
            val result = withTimeout(8_000) { state.first { it.pendingAttachments.isNotEmpty() || it.error != null } }
            assertNull(result.error)
            val attachment = result.pendingAttachments.single()
            assertEquals("image/jpeg", attachment.mime); assertEquals("image draft", result.draft)
            val decoded = BitmapFactory.decodeFile(attachment.localUri)
            assertNotNull(decoded)
            try { assertEquals(1568, decoded.width); assertEquals(784, decoded.height) }
            finally { decoded.recycle() }
        } finally { i.runOnMainSync { scope.cancel() }; source.delete(); directory.deleteRecursively() }
    }
    @Test fun realContentResolverStagesAFileWithoutChangingTheKeyboardDraft() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        val source = File(i.targetContext.cacheDir, "stage-source-${UUID.randomUUID()}").apply { writeText("attachment fixture") }
        val directory = File(i.targetContext.cacheDir, "stage-fixture-${UUID.randomUUID()}")
        val state = MutableStateFlow(CompanionState(origin = "http://synthetic", activeProfileId = "synthetic",
            openSessionId = "synthetic-thread", draft = "preserve this draft"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val stager = AttachmentStager(i.targetContext, state, scope, { 1 }, directory = directory)
        try {
            i.runOnMainSync { stager.stage(Uri.fromFile(source), "text/plain", "fixture.txt") }
            val attachment = withTimeout(5_000) { state.first { it.pendingAttachments.isNotEmpty() }.pendingAttachments.single() }
            assertEquals("attachment fixture", File(attachment.localUri).readText())
            assertEquals("preserve this draft", state.value.draft)
            assertEquals("fixture.txt", attachment.name)
        } finally { i.runOnMainSync { scope.cancel() }; source.delete(); directory.deleteRecursively() }
    }

    @Test fun aLateAttachmentCannotLandInANewConversationOnAndroid() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        val directory = File(i.targetContext.cacheDir, "stage-fixture-${UUID.randomUUID()}")
        val state = MutableStateFlow(CompanionState(origin = "http://synthetic", activeProfileId = "synthetic",
            openSessionId = "first", draft = "first draft"))
        val epoch = AtomicLong(1)
        val entered = CompletableDeferred<Unit>(); val result = CompletableDeferred<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val stager = AttachmentStager(i.targetContext, state, scope, epoch::get, directory = directory,
            read = { _, _ -> entered.complete(Unit); result.await() })
        try {
            i.runOnMainSync { stager.stage(Uri.parse("content://synthetic/file"), "text/plain", "fixture.txt") }
            withTimeout(5_000) { entered.await() }
            i.runOnMainSync { epoch.incrementAndGet(); state.value = state.value.copy(openSessionId = "second", draft = "second draft", error = "second screen") }
            val bytes = byteArrayOf(7, 8); result.complete(bytes)
            withTimeout(5_000) { while (scope.coroutineContext[Job]!!.children.any()) delay(10) }
            assertTrue(state.value.pendingAttachments.isEmpty())
            assertEquals("second draft", state.value.draft); assertEquals("second screen", state.value.error)
            assertTrue(directory.listFiles().orEmpty().isEmpty()); assertArrayEquals(ByteArray(2), bytes)
        } finally { i.runOnMainSync { scope.cancel() }; directory.deleteRecursively() }
    }
}
