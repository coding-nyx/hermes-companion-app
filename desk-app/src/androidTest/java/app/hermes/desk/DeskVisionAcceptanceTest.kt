package app.hermes.desk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.model.MessageRole
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Sends an artificial picture to an explicitly named test conversation. Never opens a camera. */
class DeskVisionAcceptanceTest {
    @get:Rule(order = 0) val quiet = DeskQuietEntryRule()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<DeskActivity>()
    @Test fun syntheticFrameReachesTheSelectedAgentBeforeItsQuestion() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("vision_fixture") == "true")
        val expectedSession = args.getString("lab_session").orEmpty()
        assumeTrue(expectedSession.isNotBlank())
        val vm = ViewModelProvider(rule.activity)[DeskViewModel::class.java]
        rule.waitUntil(30_000) { vm.state.value.connected && !vm.state.value.loading && vm.state.value.binding != null || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertEquals("Refuse to send an image to an unexpected conversation", expectedSession, vm.state.value.binding?.session)
        assertTrue(vm.state.value.canSend)
        val existing = vm.state.value.messages.map { it.id }.toSet()
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.RED; canvas.drawRect(30f, 40f, 120f, 130f, paint)
        paint.color = Color.BLUE; canvas.drawCircle(240f, 85f, 45f, paint)
        val jpeg = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output); output.toByteArray() }
        bitmap.recycle()
        rule.runOnIdle { vm.coordinator.send("This is a synthetic Desk vision test image. Describe the two colored shapes in one short sentence. Do not use tools.", jpeg) }
        rule.waitUntil(120_000) { !vm.state.value.working && !vm.state.value.turnActive || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.uncertain)
        val answer = vm.state.value.messages.last { it.role == MessageRole.ASSISTANT && it.id !in existing }.text.lowercase()
        assertTrue("The assistant did not identify the red square", "red" in answer && "square" in answer)
        assertTrue("The assistant did not identify the blue circle", "blue" in answer && "circle" in answer)
        assertFalse(vm.state.value.visionSharing)
        assertTrue(jpeg.all { it == 0.toByte() })
    }
}
