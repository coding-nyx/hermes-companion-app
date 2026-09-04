package app.hermes.companion.domain

data class DeviceAuditRow(
    val action: String,
    val app: String,
    val ok: Boolean,
    val code: String = "",
    val atMs: Long,
)

/** In-app audit mirror. Never stores typed text from device.type. */
object DeviceAudit {
    const val KEEP = 50

    fun redact(action: String, detail: String): String {
        if (action == "device.type") return ""
        return detail.take(80)
    }

    fun row(
        action: String,
        app: String,
        ok: Boolean,
        code: String = "",
        atMs: Long = System.currentTimeMillis(),
        detail: String = "",
    ): DeviceAuditRow {
        val safe = redact(action, detail)
        return DeviceAuditRow(
            action = action,
            app = app,
            ok = ok,
            code = code.ifBlank { safe },
            atMs = atMs,
        )
    }

    fun append(existing: List<DeviceAuditRow>, next: DeviceAuditRow): List<DeviceAuditRow> =
        (existing + next).takeLast(KEEP)

    fun hint(paired: Boolean, armed: Boolean): String? =
        if (paired && armed) "A paired Android device is ARMED. Prefer mobile_snapshot then mobile_click."
        else null
}
