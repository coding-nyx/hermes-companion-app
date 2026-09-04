package app.hermes.companion.domain

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewindPolicyTest {
    @Test
    fun onlyDurableUserTurnsAreTargets() {
        assertTrue(RewindPolicy.canTarget(user("11")))
        assertFalse(RewindPolicy.canTarget(user("u-local")))
        assertFalse(RewindPolicy.canTarget(user("11").copy(queued = true)))
        assertFalse(RewindPolicy.canTarget(user("11").copy(role = MessageRole.ASSISTANT)))
        assertNull(RewindPolicy.durableRowId("m1"))
        assertEquals(42L, RewindPolicy.durableRowId("42"))
    }

    @Test
    fun ordinarySubmitNeverCarriesTruncate() {
        assertEquals("", RewindPolicy.jsonExtras(null))
    }

    @Test
    fun confirmedRewindSendsRowIdAndFlag() {
        assertEquals(
            ""","truncate_before_row_id":20,"confirm_truncate":true""",
            RewindPolicy.jsonExtras(RewindSubmit(20)),
        )
        assertEquals(
            ""","truncate_before_row_id":11,"confirm_truncate":true,"confirm_empty_truncate":true""",
            RewindPolicy.jsonExtras(RewindSubmit(11, empty = true)),
        )
    }

    @Test
    fun dropFromCutsTargetAndAfter() {
        val rows = listOf(user("10"), assistant("11"), user("20"), assistant("21"))
        val kept = RewindPolicy.dropFrom(rows, "20")
        assertEquals(listOf("10", "11"), kept.map { it.id })
        assertEquals(rows, RewindPolicy.dropFrom(rows, "missing"))
    }

    @Test
    fun firstUserTurnIsOrdinalZero() {
        val rows = listOf(user("10"), assistant("11"), user("20"))
        assertTrue(RewindPolicy.isFirstUserTurn(rows, "10"))
        assertFalse(RewindPolicy.isFirstUserTurn(rows, "20"))
    }

    @Test
    fun rebindUsesFreshSurvivorIdsInUserOrder() {
        val kept = listOf(user("10"), assistant("11"))
        val rebound = RewindPolicy.rebindUserRowIds(kept, listOf(101L))
        assertEquals("101", rebound[0].id)
        assertEquals("11", rebound[1].id)
    }

    @Test
    fun nullSurvivorDropsCachedDurableId() {
        val rebound = RewindPolicy.rebindUserRowIds(listOf(user("10")), listOf(null))
        assertEquals("x-10", rebound[0].id)
        assertNull(RewindPolicy.durableRowId(rebound[0].id))
    }

    @Test
    fun survivorParserKeepsIntsAndNullsOthers() {
        assertEquals(
            listOf(7L, null, null, null, 12L),
            RewindPolicy.survivorRowIds(listOf(7, null, 9.5, "11", 12)),
        )
    }

    private fun user(id: String) = ChatMessage(id = id, role = MessageRole.USER, text = id)

    private fun assistant(id: String) =
        ChatMessage(id = id, role = MessageRole.ASSISTANT, text = id)
}
