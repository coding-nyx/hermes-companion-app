package app.hermes.hands

import android.content.Context
import android.provider.Settings
import app.hermes.companion.device.AccessibilitySettingsToggle
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.domain.DeviceLanePolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote inspection and mutation of system and Hands gates by authenticated agents.
 */
object HandsGates {

    fun get(context: Context, runtime: HandsRuntime): JSONObject {
        val st = runtime.state.value
        val notif = runtime.notifications.state.value
        val shizuku = runtime.shizuku.status.value

        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        val locked = km?.isKeyguardLocked == true
        val effectiveTarget = when (st.target) {
            "auto" -> if ((locked || !CompanionAccessibilityService.bound) && (st.backend != "accessibility" && shizuku.running && shizuku.permissionGranted)) "agent" else "primary"
            else -> st.target
        }

        val root = JSONObject()
        root.put("backend", st.backend)
        root.put("target", st.target)
        root.put("effective_target", effectiveTarget)
        root.put("display_id", st.displayId)
        root.put("accessibility_enabled", CompanionAccessibilityService.bound)
        root.put("accessibility_listed", CompanionAccessibilityService.listed(context) == true)
        root.put("shizuku_available", shizuku.running && shizuku.permissionGranted)
        root.put("secure_settings_available", CompanionAccessibilityService.canWriteSecureSettings(context))
        root.put("protected_packages", JSONArray(st.protectedPackages.sorted()))

        val streamJson = JSONObject()
        streamJson.put("enabled", notif.enabled)
        streamJson.put("packages", JSONArray(notif.packages.sorted()))
        streamJson.put("content", notif.content)
        streamJson.put("retention_minutes", notif.retentionMinutes)
        streamJson.put("include_calls", notif.includeCalls)
        root.put("notification_stream", streamJson)

        val pluggedAwake = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
        }.getOrDefault(0)
        root.put("stay_awake_plugged", pluggedAwake != 0)

        val timeout = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
        }.getOrDefault(60000)
        root.put("screen_off_timeout_ms", timeout)

        return root
    }

    fun set(context: Context, runtime: HandsRuntime, clientId: String, args: JSONObject): JSONObject {
        // 1. Backend
        if (args.has("backend")) {
            val backend = args.getString("backend")
            require(backend in setOf("accessibility", "shizuku", "hybrid")) { "invalid_backend" }
            runtime.configure(backend = backend)
        }

        // 2. Target display
        if (args.has("target")) {
            val target = args.getString("target")
            require(target in setOf("primary", "agent", "auto")) { "invalid_target" }
            runtime.configure(target = target)
        }

        // 3. Accessibility service toggle via WRITE_SECURE_SETTINGS or Shizuku
        if (args.has("accessibility_enabled")) {
            val enabled = args.getBoolean("accessibility_enabled")
            val toggle = AccessibilitySettingsToggle.forContext(context)
            var success = toggle.setEnabled(enabled)
            if (!success && runtime.shizuku.status.value.bound) {
                // If WRITE_SECURE_SETTINGS was missing, try granting it via Shizuku shell
                runCatching {
                    runtime.shizuku.execute(
                        "device.shell.exec",
                        JSONObject().put("command", "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"),
                        0
                    )
                }
                success = toggle.setEnabled(enabled)
                if (!success) {
                    // Fall back to direct settings command via Shizuku shell
                    val component = android.content.ComponentName(context, CompanionAccessibilityService::class.java).flattenToString()
                    val currentServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                    val others = currentServices.split(':').filter { it.isNotBlank() && it != component }
                    val next = if (enabled) (others + component).joinToString(":") else others.joinToString(":")
                    runtime.shizuku.execute("device.shell.exec", JSONObject().put("command", "settings put secure enabled_accessibility_services '$next'"), 0)
                    if (enabled) {
                        runtime.shizuku.execute("device.shell.exec", JSONObject().put("command", "settings put secure accessibility_enabled 1"), 0)
                    }
                    success = true
                }
            }
            if (!success && enabled) {
                throw IllegalStateException("accessibility_enable_failed")
            }
        }

        // 4. Protected packages
        if (args.has("protected_packages")) {
            val current = runtime.state.value.protectedPackages.toMutableSet()
            val raw = args.opt("protected_packages")
            when (raw) {
                is JSONArray -> {
                    val next = (0 until raw.length()).map { raw.getString(it) }.toSet()
                    runtime.protect(next)
                }
                is JSONObject -> {
                    if (raw.optBoolean("clear", false)) {
                        current.clear()
                    }
                    val add = raw.optJSONArray("add")
                    if (add != null) {
                        for (i in 0 until add.length()) current.add(add.getString(i))
                    }
                    val remove = raw.optJSONArray("remove")
                    if (remove != null) {
                        for (i in 0 until remove.length()) current.remove(remove.getString(i))
                    }
                    runtime.protect(current)
                }
                else -> throw IllegalArgumentException("invalid_protected_packages")
            }
        }

        // 5. Notification stream settings
        if (args.has("notification_stream")) {
            val notifArgs = args.getJSONObject("notification_stream")
            val enabled = notifArgs.optBoolean("enabled", runtime.notifications.state.value.enabled)
            val packages = if (notifArgs.has("packages")) {
                val arr = notifArgs.getJSONArray("packages")
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } else null
            val content = if (notifArgs.has("content")) notifArgs.getString("content") else null
            val retention = if (notifArgs.has("retention_minutes")) notifArgs.getInt("retention_minutes") else null
            val calls = if (notifArgs.has("include_calls")) notifArgs.getBoolean("include_calls") else null

            runtime.notifications.configureRemote(clientId, enabled, packages, content, retention, calls)
        }

        // 6. Stay awake while plugged in (Settings.Global)
        if (args.has("stay_awake_plugged")) {
            val enabled = args.getBoolean("stay_awake_plugged")
            val value = if (enabled) 7 else 0
            runCatching {
                Settings.Global.putInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, value)
            }
        }

        // 7. Screen timeout
        if (args.has("screen_off_timeout_ms")) {
            val ms = args.getInt("screen_off_timeout_ms").coerceIn(5000, 2147483647)
            runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
            }
        }

        // 8. Grant package permissions via Shizuku shell
        if (args.has("grant_permissions")) {
            val perms = args.getJSONArray("grant_permissions")
            if (runtime.shizuku.status.value.bound) {
                for (i in 0 until perms.length()) {
                    val perm = perms.getString(i)
                    runCatching {
                        runtime.shizuku.execute(
                            "device.shell.exec",
                            JSONObject().put("command", "pm grant ${context.packageName} $perm"),
                            0
                        )
                    }
                }
            }
        }

        // 9. AppOps configuration via Shizuku shell
        if (args.has("appops")) {
            val ops = args.getJSONObject("appops")
            if (runtime.shizuku.status.value.bound) {
                for (op in ops.keys()) {
                    val mode = ops.getString(op)
                    runCatching {
                        runtime.shizuku.execute(
                            "device.shell.exec",
                            JSONObject().put("command", "appops set ${context.packageName} $op $mode"),
                            0
                        )
                    }
                }
            }
        }

        return get(context, runtime)
    }
}
