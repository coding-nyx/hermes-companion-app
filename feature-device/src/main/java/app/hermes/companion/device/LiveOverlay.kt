package app.hermes.companion.device

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** 1px cyan inset + LIVE chip while ARMED. */
class LiveOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var frame: View? = null
    private var chip: View? = null

    fun show() {
        if (!allowed(context)) return
        if (frame != null) return
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val border = View(context).apply { setBackgroundColor(SIGNAL) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        val pad = (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val wrap = FrameLayout(context).apply {
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(SIGNAL)
            val inner = View(context).apply { setBackgroundColor(Color.TRANSPARENT) }
            addView(
                inner,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            // hairline via padding + transparent inner doesn't punch through; use foreground
            foreground = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(pad, SIGNAL)
            }
            setPadding(0, 0, 0, 0)
        }
        val chipView = TextView(context).apply {
            text = "LIVE"
            setTextColor(SIGNAL)
            textSize = 10f
            setPadding(pad * 6, pad * 2, pad * 6, pad * 2)
            setBackgroundColor(VOID)
        }
        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()
            )
        } else null
        val topInset = insets?.top ?: (pad * 32)
        val rightInset = insets?.right ?: 0
        val chipParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = topInset + (pad * 4)
            x = rightInset + (pad * 12)
        }
        runCatching {
            wm.addView(wrap, params)
            wm.addView(chipView, chipParams)
            frame = wrap
            chip = chipView
        }
        border.visibility = View.GONE
    }

    fun hide() {
        runCatching { frame?.let { wm.removeView(it) } }
        runCatching { chip?.let { wm.removeView(it) } }
        frame = null
        chip = null
    }

    companion object {
        private val SIGNAL = Color.parseColor("#00E5C3")
        private val VOID = Color.parseColor("#07080A")

        fun allowed(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
