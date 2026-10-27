package app.hermes.companion.data.local

import android.content.Context
import app.hermes.companion.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NativeBotOperationStoreTest {
    private fun prefs(name: String) = RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    @Test fun recreatedStoreKeepsExactGatewayOwnershipAndOriginalEnvelopes() {
        val p = prefs("native-operations")
        val first = BotOwner("https://gateway/a", "default"); val second = first.copy(origin = "https://gateway/b")
        val pending = NativeBotOperation("same-id", first, "send", "group", "send once", "original-thread")
        NativeBotOperationStore(p).save(pending)
        assertTrue("Creation time must be explicit in the durable envelope", p.getString("pending", "").orEmpty().contains("createdAt"))
        val recreated = NativeBotOperationStore(p)
        assertEquals(listOf(pending), recreated.list(first)); assertTrue(recreated.list(second).isEmpty())
        recreated.remove(second, pending.id); assertEquals(listOf(pending), recreated.list(first))
        recreated.remove(first, pending.id); assertTrue(recreated.list(first).isEmpty())
    }
    @Test fun corruptionAndReusingAnIdentityForDifferentWorkFailClosed() {
        val p = prefs("native-operations-corrupt"); val store = NativeBotOperationStore(p)
        val owner = BotOwner("https://gateway/a", "default")
        val operation = NativeBotOperation("key", owner, "canonical", "default")
        store.save(operation)
        assertTrue(runCatching { store.save(operation.copy(text = "different")) }.isFailure)
        p.edit().putString("pending", "not-json").commit()
        assertTrue(runCatching { NativeBotOperationStore(p).list(owner) }.isFailure)
    }
}
