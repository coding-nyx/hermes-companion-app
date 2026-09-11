package app.hermes.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import app.hermes.companion.domain.NotificationPolicy
import app.hermes.companion.domain.WakePing
import app.hermes.companion.domain.WakePolicy

/**
 * Builds the shade notification for a wake ping (A27.1).
 *
 * Replaces a single `IMPORTANCE_HIGH` "wake" channel that showed the stock system info icon and
 * a body of `profile · session_id`. An approval blocking an agent and a run quietly finishing
 * arrived identically, so the whole class of notification became ignorable. Now: five channels
 * the user can tune independently, a `MessagingStyle` card naming the agent and host, a
 * scannable badge, and one collapsed group per host.
 *
 * All routing decisions live in [NotificationPolicy] so they are unit-tested; this file only
 * turns them into Android objects.
 */
object WakeNotifier {

    /**
     * Small icon. The monochrome launcher layer is tintable white, which is what the shade
     * needs, and it is a large improvement on `ic_dialog_info`. Caveat: it is an adaptive-icon
     * layer drawn in a 108dp viewport, so the glyph renders inset at 24dp — a purpose-drawn
     * notification asset is a design follow-up, not a code one.
     */
    private val SMALL_ICON = R.drawable.ic_launcher_monochrome

    /** Per-host set of live thread ids, so the group summary can count what is actually showing. */
    private val live = mutableMapOf<String, MutableSet<Int>>()

    /**
     * Register every channel once, at process start. Doing it here rather than on the hot path
     * means a ping never races channel creation, and the user can find and tune all five in
     * Settings before the first notification ever arrives.
     */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (channel in NotificationPolicy.ALL) {
            nm.createNotificationChannel(
                NotificationChannel(channel.id, channel.title, importanceOf(channel.importance)).apply {
                    description = channel.description
                },
            )
        }
        // The old single channel is gone; delete it so it stops appearing in Settings with
        // whatever importance the user had set on it.
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL) }
    }

    private fun importanceOf(i: NotificationPolicy.Importance): Int = when (i) {
        NotificationPolicy.Importance.MIN -> NotificationManager.IMPORTANCE_MIN
        NotificationPolicy.Importance.LOW -> NotificationManager.IMPORTANCE_LOW
        NotificationPolicy.Importance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
        NotificationPolicy.Importance.HIGH -> NotificationManager.IMPORTANCE_HIGH
    }

    /**
     * @param snippet real message text when the host could be reached; empty falls back to a
     *   human phrase for the ping type. Never show the raw session id as the body.
     */
    fun show(context: Context, ping: WakePing, snippet: String = "") {
        val app = context.applicationContext as CompanionApp
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)

        val hostName = app.hostName(ping.origin)
        val channel = NotificationPolicy.channelFor(ping.type)
        val group = NotificationPolicy.groupKey(ping.origin)
        val id = NotificationPolicy.notificationId(ping.origin, ping.sessionId, ping.roomId)
        val body = NotificationPolicy.body(ping.type, snippet, ping.roomId, ping.sessionId)

        val person = Person.Builder()
            .setName(NotificationPolicy.sender(ping.profile, hostName))
            .setKey("${ping.origin}|${ping.profile}")
            .setBot(true)
            .build()

        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(WakePolicy.deepLink(ping.sessionId, ping.profile, ping.origin, ping.roomId)))
                .putExtra(MainActivity.EXTRA_NONCE, app.launchNonce)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(SMALL_ICON)
            .setStyle(
                NotificationCompat.MessagingStyle(person)
                    .setConversationTitle(NotificationPolicy.title(ping.type, ping.profile, hostName))
                    .addMessage(body, System.currentTimeMillis(), person),
            )
            // Title/text as well as the style: some launchers and Wear render the flat fields.
            .setContentTitle(NotificationPolicy.title(ping.type, ping.profile, hostName))
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(group)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(channel.importance != NotificationPolicy.Importance.HIGH)
            .build()

        nm.notify(id, notification)
        postSummary(context, nm, group, hostName, id)
    }

    /**
     * A group needs an explicit summary or Android shows members loose on API 24+. Counting live
     * ids keeps the summary honest ("3 updates from lab") instead of guessing.
     */
    private fun postSummary(
        context: Context,
        nm: NotificationManager,
        group: String,
        hostName: String,
        newId: Int,
    ) {
        val ids = synchronized(live) {
            live.getOrPut(group) { mutableSetOf() }.also { it.add(newId) }.toSet()
        }
        if (ids.size < 2) return // A lone notification reads better without a summary above it.
        val summary = NotificationCompat.Builder(context, NotificationPolicy.TURNS.id)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(hostName.ifBlank { "Hermes" })
            .setContentText(NotificationPolicy.summaryText(ids.size, hostName))
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(NotificationPolicy.summaryId(group), summary)
    }

    /** Forget a thread's notification, so the group count stops including it. */
    fun clear(context: Context, ping: WakePing) {
        val group = NotificationPolicy.groupKey(ping.origin)
        val id = NotificationPolicy.notificationId(ping.origin, ping.sessionId, ping.roomId)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(id)
        val remaining = synchronized(live) {
            live[group]?.also { it.remove(id) }?.size ?: 0
        }
        if (remaining < 2) nm.cancel(NotificationPolicy.summaryId(group))
    }

    private const val LEGACY_CHANNEL = "wake"
}
