package app.hermes.desk.vision

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import app.hermes.desk.feature.DeskBinding
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A single latest frame in memory; no preview surface, video recording or automatic uploads. */
class DeskCameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val onFrame: (DeskBinding, ByteArray, Boolean) -> Unit,
    private val onStopped: () -> Unit,
    private val onError: (String) -> Unit,
    private val onStarted: () -> Unit = {},
) {
    private val generation = AtomicLong()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var observedCamera: CameraInfo? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var closed = false

    fun start(binding: DeskBinding, front: Boolean, once: Boolean = false) {
        stop()
        if (closed) return
        val epoch = generation.get()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed || epoch != generation.get()) return@addListener
            try {
                val cameraProvider = future.get()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                require(cameraProvider.hasCamera(selector)) { "This camera is unavailable" }
                @Suppress("DEPRECATION")
                val useCase = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                var last = Long.MIN_VALUE
                var delivered = false
                useCase.setAnalyzer(worker) { image ->
                    try {
                        if (epoch != generation.get() || once && delivered) return@setAnalyzer
                        val now = SystemClock.elapsedRealtime()
                        if (last != Long.MIN_VALUE && now - last < 1_000) return@setAnalyzer
                        last = now
                        val jpeg = jpeg(image)
                        delivered = true
                        main.execute {
                            if (epoch == generation.get() && !closed) onFrame(binding, jpeg, once) else jpeg.fill(0)
                        }
                    } catch (_: Exception) {
                        main.execute { if (epoch == generation.get()) { stop(); onError("Camera capture failed; sharing stopped") } }
                    } finally { image.close() }
                }
                provider = cameraProvider; analysis = useCase
                val camera = cameraProvider.bindToLifecycle(owner, selector, useCase)
                onStarted()
                observedCamera = camera.cameraInfo
                cameraObserver = Observer<CameraState> { state ->
                    if (epoch == generation.get() && state.error != null) { stop(); onError("Camera is unavailable; sharing stopped") }
                }.also { camera.cameraInfo.cameraState.observe(owner, it) }
            } catch (_: Exception) { if (epoch == generation.get()) { stop(); onError("Could not open this camera") } }
        }, main)
    }

    fun stop() {
        generation.incrementAndGet()
        cameraObserver?.let { observedCamera?.cameraState?.removeObserver(it) }
        cameraObserver = null; observedCamera = null
        analysis?.let { it.clearAnalyzer(); provider?.unbind(it) }; analysis = null
        onStopped()
    }
    fun close() { closed = true; stop(); worker.shutdown() }

    private fun jpeg(image: ImageProxy): ByteArray {
        require(image.format == ImageFormat.YUV_420_888)
        val w = image.width; val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        var offset = 0
        for (row in 0 until h) for (col in 0 until w) nv21[offset++] = y.buffer.get(row * y.rowStride + col * y.pixelStride)
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            nv21[offset++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
            nv21[offset++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
        }
        val encoded = ByteArrayOutputStream()
        try { check(YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 78, encoded)) }
        finally { nv21.fill(0) }
        val raw = encoded.toByteArray()
        val bitmap = try { requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size)) } finally { raw.fill(0) }
        var rotated: Bitmap? = null
        return try {
            rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
            ByteArrayOutputStream().use { output ->
                check(rotated.compress(Bitmap.CompressFormat.JPEG, 78, output))
                output.toByteArray().also { require(it.size <= 1_000_000) }
            }
        } finally { if (rotated !== bitmap) rotated?.recycle(); bitmap.recycle() }
    }
}
