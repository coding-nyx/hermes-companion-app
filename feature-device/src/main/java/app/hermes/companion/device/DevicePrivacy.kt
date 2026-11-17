package app.hermes.companion.device

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Device-wide, fail-closed shield. Only an explicit phone-local action can release it. */
object DevicePrivacy {
    private const val PREFS = "hermes_device_privacy"
    private const val KEY = "private_entry_active"
    private val mutable = MutableStateFlow(true)
    val activeFlow: StateFlow<Boolean> = mutable
    val active: Boolean get() = mutable.value
    val epoch = AtomicLong()
    private var initialized = false
    // Deliberately memory-only: never put entered values in a Bundle, preferences or a log.
    private val values = ArrayDeque<String>()

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        mutable.value = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false) }.getOrDefault(true)
        initialized = true
    }

    @Synchronized fun begin(context: Context): Boolean {
        initialize(context)
        // Close concurrent readers before doing disk I/O. A persistence failure leaves us shut.
        mutable.value = true
        epoch.incrementAndGet()
        return runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).commit() }.getOrDefault(false)
    }

    @Synchronized fun releaseLocally(context: Context): Boolean {
        initialize(context)
        val persisted = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, false).commit() }.getOrDefault(false)
        if (persisted) { epoch.incrementAndGet(); mutable.value = false }
        return persisted
    }

    @Synchronized fun rememberValue(value: String) {
        if (value.isEmpty()) return
        values.remove(value)
        values.addLast(value)
        while (values.size > 32) values.removeFirst()
    }

    @Synchronized fun sanitize(text: String): String {
        if (values.isEmpty() || text.isEmpty()) return text
        val secrets = values.sortedByDescending { it.length }
        val safe = StringBuilder(text.length)
        var offset = 0
        while (offset < text.length) {
            var next = -1
            var length = 0
            for (value in secrets) {
                val found = text.indexOf(value, offset)
                if (found >= 0 && (next < 0 || found < next)) { next = found; length = value.length }
            }
            if (next < 0) { safe.append(text, offset, text.length); break }
            safe.append(text, offset, next).append("[private]")
            offset = next + length
        }
        return safe.toString()
    }

    fun allows(action: String, rawArgs: String): Boolean = when (action) {
        "device.disarm", "device.accessibility.disable" -> true
        "device.status" -> true
        "device.display.keep_awake" -> runCatching {
            val args = JSONObject(rawArgs)
            args.optString("action", "status") == "status" ||
                (args.optString("action") == "set" && args.opt("enabled") == false)
        }.getOrDefault(false)
        "device.secure_entry" -> runCatching {
            JSONObject(rawArgs).optString("action") in setOf("status", "cancel")
        }.getOrDefault(false)
        else -> false
    }
}
