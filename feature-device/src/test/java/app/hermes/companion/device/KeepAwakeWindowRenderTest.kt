package app.hermes.companion.device

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Button
import androidx.compose.ui.graphics.toArgb
import app.hermes.companion.design.CompanionColor
import java.io.File
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeepAwakeWindowRenderTest {
    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test @Config(qualifiers = "w320dp-h640dp-night-xhdpi")
    fun compactDarkIndicator() = renderVariants("dark-320", 320)

    @Test @Config(qualifiers = "w320dp-h640dp-notnight-xhdpi")
    fun compactLightIndicator() = renderVariants("light-320", 320)

    @Test @Config(qualifiers = "w600dp-h960dp-night-xhdpi")
    fun wideDarkIndicator() = renderVariants("dark-600", 600)

    @Test @Config(qualifiers = "w600dp-h960dp-notnight-xhdpi")
    fun wideLightIndicator() = renderVariants("light-600", 600)

    private fun renderVariants(name: String, widthDp: Int) {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val context = RuntimeEnvironment.getApplication()
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            val width = dp(widthDp)
            val available = width - dp(24)
            var stops = 0
            val view = KeepAwakeWindow.indicator(context) { stops++ }
            view.measure(View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            assertTrue("The indicator must fit within symmetric outer margins", view.width <= available)
            assertTrue("The complete native button must remain a 48dp touch target", view.width >= dp(48) && view.height >= dp(48))
            val button = view as Button
            val layout = button.layout
            assertNotNull(layout)
            for (line in 0 until layout.lineCount) {
                assertEquals("The stop label must remain complete", 0, layout.getEllipsisCount(line))
            }
            assertEquals("The full stop label must fit", button.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(view.performClick())
            assertEquals(1, stops)

            val bitmap = Bitmap.createBitmap(width, maxOf(dp(120), view.height + dp(16)), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(CompanionColor.Void.toArgb())
            canvas.save()
            canvas.translate((width - dp(12) - view.width).toFloat(), dp(8).toFloat())
            view.draw(canvas)
            canvas.restore()
            val output = System.getenv("HERMES_KEEP_AWAKE_RENDER_DIR")?.takeIf { it.isNotBlank() }
                ?.let(::File) ?: File(System.getProperty("java.io.tmpdir"), "hermes-keep-awake-render")
            check(output.isDirectory || output.mkdirs()) { "Could not create overlay render output directory" }
            try {
                File(output, "awake-overlay-$name-font-${scale.toInt()}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
