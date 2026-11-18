package app.hermes.hands

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.hermes.companion.device.CompanionAccessibilityService
import app.hermes.companion.device.AccessibilitySettingsToggle
import app.hermes.companion.device.FileToolbox
import app.hermes.companion.device.IntentToolbox
import app.hermes.companion.device.SpeechToolbox
import app.hermes.companion.device.SystemToolbox
import app.hermes.companion.device.TelephonyToolbox
import app.hermes.companion.domain.DeviceLanePolicy
import org.json.JSONArray
import org.json.JSONObject

/** Agent-neutral platform tools. Android grants and the caller's current permit remain authoritative. */
class HandsDeviceTools(
    context: Context,
    private val protectedPackages: () -> Set<String>,
    private val targetIsAgent: () -> Boolean = { false },
    private val fileFactory: ((() -> Boolean, () -> Set<String>) -> FileToolbox)? = null,
) {
    private val context = context.applicationContext
    private class Failure(val code: String, override val message: String) : Exception(message)

    /** null means another dispatcher owns this action. Permit failures propagate to the runtime. */
    fun execute(action: String, args: JSONObject, requirePermit: () -> Unit): JSONObject? {
        if (action !in ACTIONS) return null
        requirePermit()
        val handoff = action in ACTIVITY_HANDOFF_ACTIONS ||
            (action == "device.accessibility.enable" && !CompanionAccessibilityService.canWriteSecureSettings(context))
        if (handoff && targetIsAgent() && args.opt("allow_primary_handoff") != true) {
            requirePermit()
            return error("primary_handoff_required", "This action opens the physical phone. Set allow_primary_handoff=true only for an explicitly requested phone handoff")
        }
        val result = try {
            ok(when (action) {
                "device.noop" -> JSONObject()
                "device.files.access", "device.files.enable", "device.files" -> {
                    val armed = { requirePermit(); true }
                    val protected = { protectedPackages() + context.packageName }
                    val files = fileFactory?.invoke(armed, protected) ?: FileToolbox(context, armed, protected)
                    when (action) {
                        "device.files.access" -> files.access()
                        "device.files.enable" -> files.enable()
                        else -> files.execute(args.toString())
                    }
                }
                "device.sms.list" -> rows("messages", TelephonyToolbox.smsList(context,
                    limit = args.limit(), address = args.string("address"), query = args.string("query"),
                    sinceMs = args.long("since_ms"), box = args.string("box")))
                "device.calls.log" -> rows("calls", TelephonyToolbox.callLog(context,
                    limit = args.limit(), type = args.string("type"), sinceMs = args.long("since_ms")))
                "device.contacts.search" -> rows("contacts", TelephonyToolbox.contactsSearch(context,
                    query = args.string("query"), limit = args.limit()))
                "device.sms.send" -> {
                    val address = args.string("address").orEmpty()
                    val body = args.string("body").orEmpty()
                    if (address.isBlank() || body.isEmpty()) throw Failure("missing_args", "address and a non-empty body required")
                    requirePermit()
                    val parts = TelephonyToolbox.smsSend(context, address, body)
                    // Submission metadata only. Never echo an SMS body or claim network delivery.
                    JSONObject().put("sent", true).put("parts", parts).put("length", body.length)
                }
                "device.sms.compose", "device.calls.dial", "device.calls.place" -> {
                    val address = args.string("address").orEmpty().trim()
                    if (address.isBlank()) throw Failure("missing_args", "address required")
                    requirePermit()
                    JSONObject(IntentToolbox.launch(context, action, address, args.string("body").orEmpty()))
                }
                "device.alarm.set", "device.alarm.dismiss", "device.alarm.show", "device.alarm.snooze",
                "device.timer.set", "device.timer.dismiss", "device.timer.reset", "device.timer.show",
                "device.media.play_search", "device.calendar.add",
                "device.share", "device.settings.open", "device.apps.uninstall", "device.apps.install" -> {
                    if (action == "device.apps.uninstall") {
                        val pkg = args.string("package") ?: throw Failure("missing_args", "package required")
                        if (DeviceLanePolicy.normalizePackage(pkg)?.endsWith(".*") != false)
                            throw Failure("missing_args", "a valid package is required")
                        if (DeviceLanePolicy.isProtected(pkg, protectedPackages() + context.packageName))
                            throw Failure("protected_package", "The target app is protected")
                    }
                    val typed = IntentToolbox.Args { key -> when (val value = args.opt(key)) {
                        is String -> value
                        is Boolean -> value.toString()
                        is Number -> {
                            val text = value.toString()
                            val number = text.toDoubleOrNull()
                            if (number != null && number.isFinite() && number == Math.rint(number) && kotlin.math.abs(number) < 9.0E15)
                                number.toLong().toString() else text
                        }
                        else -> null
                    } }
                    requirePermit()
                    JSONObject(IntentToolbox.launchTask(context, action, typed))
                }
                "device.calendar.list" -> rows("events", SystemToolbox.calendarList(context,
                    fromMs = args.long("from_ms"), toMs = args.long("to_ms"), limit = args.limit()))
                "device.location.last" -> JSONObject(SystemToolbox.locationLast(context))
                "device.apps.usage" -> rows("apps", SystemToolbox.appUsage(context,
                    sinceMs = args.long("since_ms"), limit = args.limit()))
                "device.audio.dnd" -> JSONObject(SystemToolbox.dndGet(context))
                "device.audio.dnd.set" -> {
                    val mode = args.string("mode") ?: throw Failure("missing_args", "mode required")
                    requirePermit()
                    JSONObject(SystemToolbox.dndSet(context, mode))
                }
                "device.torch" -> {
                    val state = args.string("state") ?: "toggle"
                    requirePermit()
                    JSONObject(SystemToolbox.torch(context, state))
                }
                "device.voice.speak" -> {
                    val text = args.string("text").orEmpty()
                    if (text.isBlank()) throw Failure("missing_args", "text required")
                    requirePermit()
                    JSONObject(SpeechToolbox.speak(context, text,
                        queue = args.boolean("queue") ?: false, rate = args.float("rate"), pitch = args.float("pitch"),
                        waitMs = args.long("wait_ms") ?: 0L))
                }
                "device.voice.stop" -> JSONObject().put("stopped", SpeechToolbox.stop())
                "device.voice.status" -> JSONObject(SpeechToolbox.status(context))
                "device.accessibility.status" -> accessibilityStatus()
                "device.accessibility.enable" -> {
                    val wasOff = CompanionAccessibilityService.explicitlyOff(context)
                    requirePermit()
                    if (CompanionAccessibilityService.canWriteSecureSettings(context)) {
                        val wasListed = CompanionAccessibilityService.listed(context) == true
                        val toggle = AccessibilitySettingsToggle.forContext(context)
                        // The service rejects binding while durable OFF is set. Clear it only
                        // for this explicit request; restore it if the guarded operation fails.
                        CompanionAccessibilityService.setExplicitlyOff(context, false)
                        try {
                            if (!CompanionAccessibilityService.canWriteSecureSettings(context))
                                throw Failure("permission_denied", "Hands needs WRITE_SECURE_SETTINGS")
                            if (!toggle.setEnabled(true, requirePermit)) throw Failure("permission_denied", "Android refused to enable Hands Accessibility")
                            requirePermit()
                            if (wasListed && !CompanionAccessibilityService.bound) {
                                fun enablePermit() {
                                    requirePermit()
                                    if (CompanionAccessibilityService.explicitlyOff(context))
                                        throw Failure("accessibility_disabled", "Hands Accessibility was switched off")
                                }
                                val rebound = toggle.rebindListed(::enablePermit)
                                enablePermit()
                                if (!rebound) throw Failure("permission_denied", "Android refused to reconnect Hands Accessibility")
                            }
                        } catch (failure: Exception) {
                            if (wasOff) CompanionAccessibilityService.setExplicitlyOff(context, true)
                            if ((!wasListed || wasOff) && !toggle.setEnabled(false)) {
                                // If the grant disappeared during rollback, durable OFF also
                                // prevents a late Android service bind from re-enabling it.
                                CompanionAccessibilityService.setExplicitlyOff(context, true)
                                CompanionAccessibilityService.requestOff(context)
                            }
                            throw failure
                        }
                        accessibilityStatus().put("requested", true).put("settings_opened", false)
                            .put("enabled_automatically", true).put("permission_granted_automatically", false)
                    } else {
                        // Runtime normally handles this as a disarming local-approval handoff.
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        accessibilityStatus().put("requested", true).put("settings_opened", true)
                            .put("permission_granted_automatically", false)
                    }
                }
                "device.accessibility.disable" -> {
                    // Runtime MUST revoke all controller leases before dispatching this stop action.
                    requirePermit()
                    CompanionAccessibilityService.setExplicitlyOff(context, true)
                    if (!CompanionAccessibilityService.requestOff(context)) throw Failure("permission_denied",
                        "Hands is disarmed and Accessibility OFF is saved; disable Hands manually in Android Accessibility settings")
                    accessibilityStatus().put("requested", true)
                }
                else -> throw Failure("unsupported_action", "Unknown device action")
            })
        } catch (failure: Exception) {
            // Preserve revocation/privacy/pairing errors from callbacks instead of translating
            // them into a provider error or publishing data obtained after consent ended.
            requirePermit()
            failure(action, failure)
        }
        requirePermit()
        if (result.optBoolean("ok") && handoff)
            result.getJSONObject("result").put("handoff_target", "primary")
        return result
    }

    private fun accessibilityStatus(): JSONObject = JSONObject()
        .put("a11y_bound", CompanionAccessibilityService.bound)
        .put("listed", CompanionAccessibilityService.listed(context) ?: JSONObject.NULL)
        .put("explicitly_off", CompanionAccessibilityService.explicitlyOff(context))
        .put("can_write_secure_settings", CompanionAccessibilityService.canWriteSecureSettings(context))

    private fun rows(key: String, values: List<Map<String, Any?>>): JSONObject = JSONObject()
        .put(key, JSONArray(values.map { JSONObject(it) })).put("count", values.size)

    private fun failure(action: String, failure: Exception): JSONObject {
        val code = when {
            failure is Failure -> failure.code
            action.startsWith("device.files") -> FileToolbox.errorCode(failure)
            action in TELEPHONY -> TelephonyToolbox.errorCode(failure)
            action in HANDOFFS -> IntentToolbox.errorCode(failure)
            action.startsWith("device.voice.") -> SpeechToolbox.errorCode(failure)
            action.startsWith("device.accessibility.") -> if (failure is SecurityException) "permission_denied" else "handoff_failed"
            else -> SystemToolbox.errorCode(failure)
        }
        val message = when {
            failure is Failure -> failure.message
            action in TELEPHONY -> TelephonyToolbox.errorMessage(failure)
            action in SYSTEM -> SystemToolbox.errorMessage(failure)
            else -> failure.message ?: "Device operation failed"
        }.replace("Hermes Companion", "Hands").replace("Companion", "Hands")
        return error(code, message)
    }

    private fun JSONObject.string(key: String): String? = opt(key) as? String
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.float(key: String): Float? = if (has(key) && !isNull(key)) getDouble(key).toFloat().also { require(it.isFinite()) } else null
    private fun JSONObject.boolean(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
    private fun JSONObject.limit(): Int = (long("limit") ?: 20).coerceIn(1, 200).toInt()

    companion object {
        private val TELEPHONY = setOf("device.sms.list", "device.sms.send", "device.calls.log", "device.contacts.search")
        private val HANDOFFS = setOf(
            "device.sms.compose", "device.calls.dial", "device.calls.place",
            "device.alarm.set", "device.alarm.dismiss", "device.alarm.show", "device.alarm.snooze",
            "device.timer.set", "device.timer.dismiss", "device.timer.reset", "device.timer.show",
            "device.media.play_search", "device.calendar.add", "device.share", "device.settings.open",
            "device.apps.uninstall", "device.apps.install"
        )
        private val SYSTEM = setOf(
            "device.calendar.list", "device.location.last", "device.apps.usage",
            "device.audio.dnd", "device.audio.dnd.set", "device.torch"
        )
        val ACTIONS = TELEPHONY + HANDOFFS + SYSTEM + setOf("device.noop", "device.files.access", "device.files.enable", "device.files",
            "device.voice.speak", "device.voice.stop", "device.voice.status", "device.accessibility.status", "device.accessibility.enable", "device.accessibility.disable")
        val META_ACTIONS = setOf("device.files.access", "device.voice.status", "device.audio.dnd", "device.accessibility.status")
        val STOP_ACTIONS = setOf("device.accessibility.disable")
        val ACTIVITY_HANDOFF_ACTIONS = HANDOFFS + setOf("device.files.enable")
    }
}
