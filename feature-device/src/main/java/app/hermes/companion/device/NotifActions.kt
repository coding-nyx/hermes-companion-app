package app.hermes.companion.device

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * L0 tools that act on a shade notification instead of tapping its UI.
 *
 * This is the payoff of the notification-listener grant the app already holds: an agent can
 * answer a chat, hit "Mark as read" or clear an alert without the accessibility lane and
 * without bringing the owning app to the foreground.
 *
 * Every entry point takes the *hashed* stream key the agent was given, never a platform key —
 * [NotifStreamBus.realKey] maps it back, and only notifications that actually reached the
 * stream are in that map, so a protected or denylisted app stays unaddressable here too.
 *
 * All calls are cheap and synchronous; the coroutine dispatch happens in the coordinator.
 */
object NotifActions {

    /** Why a call could not proceed. Kept separate from "it ran and failed". */
    sealed interface Failure {
        /** The notification listener is not bound — the user has not granted it. */
        data object NoListener : Failure

        /** The hashed key is unknown: never streamed, or evicted/removed since. */
        data object UnknownKey : Failure

        /** The notification is gone from the shade. */
        data object NotActive : Failure

        /** No action at that index / no action accepting text. */
        data class NoAction(val detail: String) : Failure

        /** The owning app's PendingIntent was already cancelled. */
        data object Cancelled : Failure
    }

    /** One actionable button on a notification. */
    data class ActionRow(
        val index: Int,
        val title: String,
        /** True when the action expects typed text — use [reply], not [fireAction]. */
        val acceptsText: Boolean,
    )

    /**
     * List the buttons on a notification so the agent can choose one by index.
     * Read-only, so this is safe to allow while the lane is disarmed.
     */
    fun listActions(hashedKey: String): Result<List<ActionRow>> {
        val sbn = resolve(hashedKey).getOrElse { return Result.failure(it) }
        val actions = sbn.notification?.actions ?: return Result.success(emptyList())
        return Result.success(
            actions.mapIndexed { i, a ->
                ActionRow(
                    index = i,
                    title = a.title?.toString().orEmpty(),
                    acceptsText = !a.remoteInputs.isNullOrEmpty(),
                )
            },
        )
    }

    /**
     * Fire a plain notification button by index.
     *
     * Refuses an action that expects typed text: sending its intent with no RemoteInput bundle
     * is what produces an empty message in the user's chat, so [reply] is required instead.
     */
    fun fireAction(ctx: Context, hashedKey: String, index: Int): Result<String> {
        val sbn = resolve(hashedKey).getOrElse { return Result.failure(it) }
        val actions = sbn.notification?.actions
        val action = actions?.getOrNull(index)
            ?: return fail(Failure.NoAction("no action at index $index (${actions?.size ?: 0} available)"))
        if (!action.remoteInputs.isNullOrEmpty()) {
            return fail(
                Failure.NoAction(
                    "action $index expects text; use the reply tool so the message is not sent empty",
                ),
            )
        }
        return runCatching {
            action.actionIntent.send()
            action.title?.toString().orEmpty()
        }.recoverCatching { t ->
            if (t is PendingIntent.CanceledException) throw FailureException(Failure.Cancelled) else throw t
        }
    }

    /**
     * Reply to a notification through its RemoteInput action — the supported way to answer a
     * message without opening the app.
     *
     * Fills *every* result key the action declares: an app may declare more than one, and a
     * missing key is delivered as a null the receiving app often treats as an empty message.
     */
    fun reply(ctx: Context, hashedKey: String, text: String): Result<String> {
        val sbn = resolve(hashedKey).getOrElse { return Result.failure(it) }
        val actions = sbn.notification?.actions.orEmpty()
        val action = actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: return fail(Failure.NoAction("this notification has no reply action"))
        val inputs = action.remoteInputs ?: return fail(Failure.NoAction("no remote inputs"))

        val intent = Intent()
        val results = Bundle()
        for (input in inputs) results.putCharSequence(input.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        // Tells the receiving app a human typed this rather than picking a canned choice;
        // some messaging apps drop a reply whose source is unset.
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)

        return runCatching {
            action.actionIntent.send(ctx, 0, intent)
            action.title?.toString().orEmpty()
        }.recoverCatching { t ->
            if (t is PendingIntent.CanceledException) throw FailureException(Failure.Cancelled) else throw t
        }
    }

    /** Clear a notification from the shade. */
    fun dismiss(hashedKey: String): Result<Unit> {
        val listener = NotifStreamBus.listener ?: return fail(Failure.NoListener)
        val real = NotifStreamBus.realKey(hashedKey) ?: return fail(Failure.UnknownKey)
        listener.cancelNotification(real)
        NotifStreamBus.forgetKey(real)
        return Result.success(Unit)
    }

    /**
     * Snooze a notification for [durationMs]. The platform re-posts it when the time is up.
     * Requires API 26; `minSdk` here is 31, so it is always available.
     */
    fun snooze(hashedKey: String, durationMs: Long): Result<Long> {
        val listener = NotifStreamBus.listener ?: return fail(Failure.NoListener)
        val real = NotifStreamBus.realKey(hashedKey) ?: return fail(Failure.UnknownKey)
        val clamped = durationMs.coerceIn(MIN_SNOOZE_MS, MAX_SNOOZE_MS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return fail(Failure.NoAction("snooze needs API 26"))
        }
        listener.snoozeNotification(real, clamped)
        return Result.success(clamped)
    }

    // ---------- internals ----------

    /** Wraps a [Failure] so it can travel through `runCatching`. */
    class FailureException(val failure: Failure) : Exception(failure.toString())

    private fun <T> fail(f: Failure): Result<T> = Result.failure(FailureException(f))

    /**
     * Fetch the live notification for a hashed key. Deliberately re-reads from the platform
     * rather than caching [Notification] objects, which go stale as apps update them.
     */
    private fun resolve(hashedKey: String): Result<StatusBarNotification> {
        val listener = NotifStreamBus.listener ?: return fail(Failure.NoListener)
        val real = NotifStreamBus.realKey(hashedKey) ?: return fail(Failure.UnknownKey)
        val active = runCatching { listener.getActiveNotifications(arrayOf(real)) }.getOrNull()
        val sbn = active?.firstOrNull() ?: return fail(Failure.NotActive)
        return Result.success(sbn)
    }

    /** Map a failure to the stable error code the lane reports to the agent. */
    fun errorCode(t: Throwable?): String = when ((t as? FailureException)?.failure) {
        Failure.NoListener -> "nls_unavailable"
        Failure.UnknownKey -> "unknown_notification"
        Failure.NotActive -> "notification_gone"
        is Failure.NoAction -> "no_such_action"
        Failure.Cancelled -> "action_cancelled"
        null -> "action_failed"
    }

    fun errorMessage(t: Throwable?): String {
        val f = (t as? FailureException)?.failure
        return when (f) {
            is Failure.NoAction -> f.detail
            Failure.NoListener -> "notification access not granted"
            Failure.UnknownKey -> "no notification with that key; call the notifications tool first"
            Failure.NotActive -> "that notification is no longer in the shade"
            Failure.Cancelled -> "the owning app cancelled this action"
            null -> t?.message ?: "action failed"
        }
    }

    const val MIN_SNOOZE_MS = 1_000L
    const val MAX_SNOOZE_MS = 24 * 60 * 60 * 1000L
}
