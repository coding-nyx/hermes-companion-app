package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

object ImageCompress {
    fun jpeg(bytes: ByteArray, maxEdge: Int = 1568, quality: Int = 85, maxBytes: Int = 1_500_000): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scaled = if (longest > maxEdge) {
            val ratio = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        var q = quality
        var out: ByteArray
        do {
            val buf = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, q, buf)
            out = buf.toByteArray()
            q -= 10
        } while (out.size > maxBytes && q >= 40)
        return out
    }
}
