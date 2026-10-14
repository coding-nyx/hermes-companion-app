package app.hermes.companion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCompressTest {
    @Test fun largeImagesRemainValidJpegWithTheExistingEdgeAndAspectRatio() {
        val source = Bitmap.createBitmap(3840, 1920, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, png); source.recycle()
        val jpeg = ImageCompress.jpeg(png.toByteArray())
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull(decoded)
        try { assertEquals(1568, decoded.width); assertEquals(784, decoded.height) }
        finally { decoded.recycle() }
    }

    @Test fun invalidImagesFailInsteadOfBeingLabeledAsJpeg() {
        assertTrue(runCatching { ImageCompress.jpeg("invalid image".toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }
}
