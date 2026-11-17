package app.hermes.hands

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import app.hermes.companion.domain.DeviceLanePolicy
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, bounded settings operations. No arbitrary setting keys or shell commands. */
class HandsSecureSettingsTools internal constructor(
    private val context: Context,
    private val protectedPackages: () -> Set<String>,
    private val platform: Platform = AndroidPlatform(context),
) {
    data class Service(val component: ComponentName, val label: String, val bindingPermission: String?)
    internal interface Platform {
        fun granted(): Boolean
        fun installed(): List<Service>
        fun read(key: String): String?
        fun write(key: String, value: String): Boolean
    }

    /** Android remains the source of truth; WSS is separate from WRITE_SETTINGS and DND access. */
    private class AndroidPlatform(private val context: Context) : Platform {
        override fun granted() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        override fun installed(): List<Service> = context.getSystemService(AccessibilityManager::class.java)
            .installedAccessibilityServiceList.mapNotNull { info: AccessibilityServiceInfo ->
                val entry = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
                val component = ComponentName(entry.packageName, entry.name)
                val label = runCatching { info.resolveInfo.loadLabel(context.packageManager).toString() }.getOrDefault(entry.packageName)
                Service(component, label.take(160), entry.permission)
            }
        override fun read(key: String): String? = Settings.Secure.getString(context.contentResolver, key)
        override fun write(key: String, value: String): Boolean = Settings.Secure.putString(context.contentResolver, key, value)
    }

    fun execute(action: String, args: JSONObject, requirePermit: () -> Unit): JSONObject? {
        if (action !in ACTIONS) return null
        var permitFailure: Exception? = null
        fun guard() {
            try { requirePermit() } catch (failure: Exception) { permitFailure = failure; throw failure }
        }
        guard()
        return try {
            val data = when (action) {
                "device.accessibility.services" -> { only(args, emptySet()); listServices() }
                "device.accessibility.service.set" -> {
                    only(args, setOf("component", "enabled"))
                    val component = args.opt("component") as? String ?: throw Bad("invalid_args")
                    val enabled = args.opt("enabled") as? Boolean ?: throw Bad("invalid_args")
                    setService(component, enabled, ::guard)
                }
                "device.secure_settings.list" -> { only(args, emptySet()); listSettings() }
                "device.secure_settings.set" -> {
                    only(args, setOf("setting", "enabled"))
                    val setting = args.opt("setting") as? String ?: throw Bad("invalid_args")
                    val enabled = args.opt("enabled") as? Boolean ?: throw Bad("invalid_args")
                    setSetting(setting, enabled, ::guard)
                }
                else -> error("unknown action")
            }
            guard()
            ok(data)
        } catch (error: Exception) {
            permitFailure?.let { throw it }
            guard()
            val code = when (error) {
                is Bad -> error.code
                is SecurityException -> if (action.endsWith(".set") && !platform.granted()) "permission_denied" else "settings_unavailable"
                is IllegalArgumentException -> "invalid_args"
                else -> "settings_unavailable"
            }
            app.hermes.hands.error(code, when (code) {
                "permission_denied" -> "Hands needs WRITE_SECURE_SETTINGS granted locally using an authorized ADB setup."
                "protected_package" -> "This accessibility service belongs to a protected app."
                "own_service" -> "Use the dedicated Hands Accessibility enable or disable tool."
                "unknown_service" -> "Choose an installed accessibility service returned by the services tool."
                "setting_unverified" -> "Android did not confirm this setting; inspect its current value before retrying."
                "invalid_args" -> "Use only the documented setting and component fields."
                else -> "Android could not complete the settings operation."
            })
        }
    }

    private fun listServices(): JSONObject {
        val enabled = enabledComponents()
        val services = platform.installed().filter(::validService)
            .filterNot { DeviceLanePolicy.isProtected(it.component.packageName, protectedPackages()) }
            .sortedBy { it.component.flattenToString() }
        return JSONObject().put("services", JSONArray().also { rows -> services.forEach { service ->
            rows.put(JSONObject().put("component", service.component.flattenToString()).put("label", service.label)
                .put("enabled", enabled.contains(service.component)).put("hands_service", service.component.packageName == context.packageName))
        } }).put("can_write_secure_settings", platform.granted())
    }

    private fun setService(raw: String, enabled: Boolean, requirePermit: () -> Unit): JSONObject {
        if (raw.length !in 3..512) throw Bad("invalid_args")
        val component = ComponentName.unflattenFromString(raw) ?: throw Bad("invalid_args")
        if (component.packageName == context.packageName) throw Bad("own_service")
        if (DeviceLanePolicy.isProtected(component.packageName, protectedPackages())) throw Bad("protected_package")
        val service = platform.installed().firstOrNull { it.component == component && validService(it) } ?: throw Bad("unknown_service")
        checkGrant()
        val previousRaw = platform.read(ENABLED_SERVICES).orEmpty()
        val previous = previousRaw.split(':').filter { it.isNotBlank() }
        val others = previous.filter { ComponentName.unflattenFromString(it) != service.component }
        val next = if (enabled) others + service.component.flattenToString() else others
        val changed = next != previous
        val added = enabled && previous.none { ComponentName.unflattenFromString(it) == component }
        var attemptedAddition = false
        var completed = false
        try {
            requirePermit(); checkGrant()
            if (DeviceLanePolicy.isProtected(component.packageName, protectedPackages())) throw Bad("protected_package")
            if (changed) {
                attemptedAddition = added
                if (!platform.write(ENABLED_SERVICES, next.joinToString(":"))) throw Bad("setting_unverified")
            }
            if (enabled) {
                requirePermit(); checkGrant()
                if (!platform.write(ACCESSIBILITY_ENABLED, "1")) throw Bad("setting_unverified")
            }
            requirePermit(); checkGrant()
            if ((component in enabledComponents()) != enabled) throw Bad("setting_unverified")
            completed = true
            return JSONObject().put("component", component.flattenToString()).put("enabled", enabled).put("changed", changed)
                .put("listed", enabled).put("note", "Android may connect or disconnect the service asynchronously.")
        } finally {
            if (!completed && attemptedAddition) {
                // The permit may already be revoked. Cleanup is allowed to undo only the
                // component this operation added, preserving other concurrent list edits.
                runCatching {
                    val now = platform.read(ENABLED_SERVICES).orEmpty().split(':').filter { it.isNotBlank() }
                    val restored = now.filter { ComponentName.unflattenFromString(it) != component }
                    if (restored != now) platform.write(ENABLED_SERVICES, restored.joinToString(":"))
                }
            }
        }
    }

    private fun listSettings() = JSONObject().put("can_write_secure_settings", platform.granted())
        .put("settings", JSONArray().also { rows -> SECURE_SETTINGS.forEach { (name, key) ->
            rows.put(JSONObject().put("setting", name).put("enabled", readBoolean(key) ?: JSONObject.NULL))
        } })

    private fun setSetting(name: String, enabled: Boolean, requirePermit: () -> Unit): JSONObject {
        val key = SECURE_SETTINGS[name] ?: throw Bad("invalid_args")
        checkGrant(); requirePermit(); checkGrant()
        if (!platform.write(key, if (enabled) "1" else "0")) throw Bad("setting_unverified")
        requirePermit(); checkGrant()
        if (readBoolean(key) != enabled) throw Bad("setting_unverified")
        return JSONObject().put("setting", name).put("enabled", enabled).put("verified", true)
    }

    private fun readBoolean(key: String): Boolean? = runCatching { platform.read(key) }.getOrNull().let {
        when (it) { "1" -> true; "0" -> false; else -> null }
    }
    private fun enabledComponents(): Set<ComponentName> = platform.read(ENABLED_SERVICES).orEmpty().split(':')
        .mapNotNull(ComponentName::unflattenFromString).toSet()
    private fun validService(service: Service) = service.bindingPermission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
    private fun checkGrant() { if (!platform.granted()) throw Bad("permission_denied") }
    private fun only(args: JSONObject, fields: Set<String>) { if (args.keys().asSequence().any { it !in fields }) throw Bad("invalid_args") }
    private class Bad(val code: String) : RuntimeException(code)

    companion object {
        private const val ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        private const val ACCESSIBILITY_ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED
        internal val SECURE_SETTINGS = linkedMapOf(
            "display_inversion" to Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
            // Hidden as an SDK constant but readable/writable in AOSP; OEMs may not expose it.
            "show_keyboard_with_hardware_keyboard" to "show_ime_with_hard_keyboard",
        )
        val ACTIONS = setOf("device.accessibility.services", "device.accessibility.service.set",
            "device.secure_settings.list", "device.secure_settings.set")
    }
}
