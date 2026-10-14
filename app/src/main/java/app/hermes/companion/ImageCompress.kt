package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

object ImageCompress {
    fun jpeg(bytes: ByteArray, maxEdge: Int = 1568, quality: Int = 85, maxBytes: Int = 1_500_000): ByteArray {
        require(maxEdge > 0 && maxEdge <= Int.MAX_VALUE / 2)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not decode image" }
        val options = BitmapFactory.Options()
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (edge / sample > maxEdge * 2) sample *= 2
        options.inSampleSize = sample
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Could not decode image")
        var scaled: Bitmap? = null
        try {
            val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
            val ready = if (longest > maxEdge) {
                val ratio = maxEdge.toFloat() / longest
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            } else bitmap
            scaled = ready
            var q = quality
            var out: ByteArray
            do {
                val buf = ByteArrayOutputStream()
                check(ready.compress(Bitmap.CompressFormat.JPEG, q, buf)) { "Could not encode image" }
                out = buf.toByteArray()
                q -= 10
            } while (out.size > maxBytes && q >= 40)
            return out
        } finally { if (scaled !== bitmap) scaled?.recycle(); bitmap.recycle() }
    }
}
