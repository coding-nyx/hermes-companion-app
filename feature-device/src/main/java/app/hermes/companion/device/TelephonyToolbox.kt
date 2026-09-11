package app.hermes.companion.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * L0 tools over the messaging, call-log and contacts providers.
 *
 * These are plain `ContentResolver` queries and one `SmsManager` call — no accessibility, no
 * app UI. What makes them different from the rest of the L0 lane is disclosure: a single
 * `sms.list` hands an agent the user's message history, so every entry point here reports a
 * precise [Denied] reason instead of an empty list when a permission is missing, and the lane
 * requires `ARMED` even for the reads (see `DeviceLanePolicy.L0_PASSIVE`, which deliberately
 * omits them).
 *
 * `READ_SMS`, `SEND_SMS` and `READ_CALL_LOG` are *hard-restricted*: the installer must
 * allowlist them or the grant is refused however the user answers. `adb install` does
 * allowlist them (verified on the S22: `granted=true, RESTRICTION_INSTALLER_EXEMPT`), which is
 * why these are buildable here without holding the default-SMS role.
 */
object TelephonyToolbox {

    /** A permission the user has not granted, named so the agent can ask for the right one. */
    data class Denied(val permission: String) : Exception("missing $permission")

    private fun require(ctx: Context, permission: String) {
        if (ContextCompat.checkSelfPermission(ctx, permission) != PackageManager.PERMISSION_GRANTED) {
            throw Denied(permission)
        }
    }

    private fun <T> Cursor.map(limit: Int, row: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(minOf(limit, 64))
        use { c ->
            while (c.moveToNext() && out.size < limit) out.add(row(c))
        }
        return out
    }

    private fun Cursor.str(name: String): String =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it).orEmpty() }

    private fun Cursor.long(name: String): Long =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) 0L else getLong(it) }

    private fun Cursor.int(name: String): Int =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) 0 else getInt(it) }

    // ---------- SMS ----------

    private const val BODY_CAP = 2_000

    fun smsTypeName(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
        else -> "all"
    }

    /**
     * Most recent messages first.
     *
     * @param address only messages to/from this number (substring match, so a local-format
     *   number still matches an internationally stored one).
     * @param query case-insensitive substring of the body.
     * @param sinceMs only messages newer than this epoch-millis.
     * @param box "inbox", "sent", or null for both.
     */
    fun smsList(
        ctx: Context,
        limit: Int = 20,
        address: String? = null,
        query: String? = null,
        sinceMs: Long? = null,
        box: String? = null,
    ): List<Map<String, Any?>> {
        require(ctx, Manifest.permission.READ_SMS)
        val where = StringBuilder()
        val args = ArrayList<String>()
        fun and(clause: String, vararg values: String) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append(clause)
            args.addAll(values)
        }
        if (!address.isNullOrBlank()) and("${Telephony.Sms.ADDRESS} LIKE ?", "%${address.trim()}%")
        if (!query.isNullOrBlank()) and("${Telephony.Sms.BODY} LIKE ?", "%${query.trim()}%")
        if (sinceMs != null && sinceMs > 0) and("${Telephony.Sms.DATE} > ?", sinceMs.toString())
        when (box?.trim()?.lowercase()) {
            "inbox" -> and("${Telephony.Sms.TYPE} = ?", Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
            "sent" -> and("${Telephony.Sms.TYPE} = ?", Telephony.Sms.MESSAGE_TYPE_SENT.toString())
        }
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID,
            ),
            where.takeIf { it.isNotEmpty() }?.toString(),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${Telephony.Sms.DATE} DESC",
        ) ?: return emptyList()
        return cursor.map(limit.coerceIn(1, 200)) { c ->
            mapOf(
                "id" to c.long(Telephony.Sms._ID),
                "address" to c.str(Telephony.Sms.ADDRESS),
                "body" to c.str(Telephony.Sms.BODY).take(BODY_CAP),
                "date_ms" to c.long(Telephony.Sms.DATE),
                "type" to smsTypeName(c.int(Telephony.Sms.TYPE)),
                "read" to (c.int(Telephony.Sms.READ) == 1),
                "thread_id" to c.long(Telephony.Sms.THREAD_ID),
            )
        }
    }

    /**
     * Send a text message, splitting anything over one SMS into a multipart message.
     *
     * This puts a real message on the network from the user's number and cannot be recalled,
     * which is why the lane requires `ARMED` and the tool description tells the agent to
     * confirm the wording first. Returns the part count actually submitted.
     */
    fun smsSend(ctx: Context, address: String, body: String): Int {
        require(ctx, Manifest.permission.SEND_SMS)
        val to = address.trim()
        if (to.isEmpty()) throw IllegalArgumentException("address required")
        if (body.isEmpty()) throw IllegalArgumentException("body required")
        val sms = ctx.getSystemService(SmsManager::class.java)
            ?: throw IllegalStateException("no SmsManager (no telephony on this device?)")
        val parts = sms.divideMessage(body)
        if (parts.size <= 1) {
            sms.sendTextMessage(to, null, body, null, null)
        } else {
            sms.sendMultipartTextMessage(to, null, parts, null, null)
        }
        return parts.size
    }

    // ---------- Call log ----------

    fun callTypeName(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "unknown"
    }

    /** Most recent calls first. [type] filters to incoming/outgoing/missed. */
    fun callLog(
        ctx: Context,
        limit: Int = 20,
        type: String? = null,
        sinceMs: Long? = null,
    ): List<Map<String, Any?>> {
        require(ctx, Manifest.permission.READ_CALL_LOG)
        val where = StringBuilder()
        val args = ArrayList<String>()
        fun and(clause: String, value: String) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append(clause)
            args.add(value)
        }
        when (type?.trim()?.lowercase()) {
            "incoming" -> and("${CallLog.Calls.TYPE} = ?", CallLog.Calls.INCOMING_TYPE.toString())
            "outgoing" -> and("${CallLog.Calls.TYPE} = ?", CallLog.Calls.OUTGOING_TYPE.toString())
            "missed" -> and("${CallLog.Calls.TYPE} = ?", CallLog.Calls.MISSED_TYPE.toString())
        }
        if (sinceMs != null && sinceMs > 0) and("${CallLog.Calls.DATE} > ?", sinceMs.toString())
        val cursor = ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
            ),
            where.takeIf { it.isNotEmpty() }?.toString(),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${CallLog.Calls.DATE} DESC",
        ) ?: return emptyList()
        return cursor.map(limit.coerceIn(1, 200)) { c ->
            mapOf(
                "id" to c.long(CallLog.Calls._ID),
                "number" to c.str(CallLog.Calls.NUMBER),
                "name" to c.str(CallLog.Calls.CACHED_NAME),
                "type" to callTypeName(c.int(CallLog.Calls.TYPE)),
                "date_ms" to c.long(CallLog.Calls.DATE),
                "duration_s" to c.long(CallLog.Calls.DURATION),
            )
        }
    }

    // ---------- Contacts ----------

    /**
     * Search contacts by name or number. This is what turns "text Amma" into a number, so it
     * is the piece that makes the messaging tools usable by name.
     *
     * Results are not ordered by interaction frequency: "Starting in Android 10, the platform
     * doesn't keep track of contacts affinity information."
     */
    fun contactsSearch(ctx: Context, query: String?, limit: Int = 20): List<Map<String, Any?>> {
        require(ctx, Manifest.permission.READ_CONTACTS)
        val q = query?.trim().orEmpty()
        val where: String?
        val args: Array<String>?
        if (q.isEmpty()) {
            where = null
            args = null
        } else {
            where = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?" +
                " OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            args = arrayOf("%$q%", "%$q%")
        }
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            ),
            where,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
        ) ?: return emptyList()
        val seen = HashSet<String>()
        return cursor.map(limit.coerceIn(1, 200)) { c ->
            mapOf(
                "contact_id" to c.long(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                "name" to c.str(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY),
                "number" to c.str(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "label" to phoneTypeName(c.int(ContactsContract.CommonDataKinds.Phone.TYPE)),
            )
        }.filter { seen.add("${it["name"]}|${it["number"]}") }
    }

    fun phoneTypeName(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
        else -> "other"
    }

    /** Map a thrown failure to the stable error code the lane reports. */
    fun errorCode(t: Throwable?): String = when (t) {
        is Denied -> "permission_denied"
        is IllegalArgumentException -> "missing_args"
        is SecurityException -> "permission_denied"
        else -> "query_failed"
    }

    fun errorMessage(t: Throwable?): String = when (t) {
        is Denied -> "grant ${t.permission} — adb shell pm grant ${'$'}PKG ${t.permission}, or Settings > Apps > Hermes Companion > Permissions"
        else -> t?.message ?: "query failed"
    }
}
