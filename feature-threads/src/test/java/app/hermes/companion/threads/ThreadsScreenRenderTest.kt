package app.hermes.companion.threads

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.domain.SessionLists
import app.hermes.companion.domain.ThreadSort
import app.hermes.companion.model.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadsScreenRenderTest {
    @Test fun conversationFailureRemainsRecoverableBelowTheRoomList() {
        var retries = 0
        val rooms = (1..8).map { app.hermes.companion.model.RoomRef("room-$it", "Planning room $it") }
        rule.setContent { CompanionTheme { ThreadsScreen(sessions = emptyList(), rooms = rooms,
            error = "Connection interrupted", onRetry = { retries++ }, nowMs = now) } }
        rule.onNodeWithTag("threads.rail").performScrollToNode(hasTestTag("threads.failed"))
        rule.onNodeWithTag("fetch.retry").assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test fun manyRoomsDoNotPushTheConversationListOutsideItsViewport() {
        val rooms = (1..8).map { app.hermes.companion.model.RoomRef("room-$it", "Planning room $it") }
        rule.setContent { CompanionTheme { ThreadsScreen(sessions = rows, rooms = rooms, nowMs = now) } }
        rule.onNodeWithTag("threads.rail").performScrollToNode(hasTestTag("threads.row.fresh"))
        rule.onNodeWithTag("threads.row.fresh").assertIsDisplayed()
        rule.onNodeWithTag("threads.new").assertIsDisplayed()
    }

    @Test
    fun nativeHostKeepsNewThreadWithoutOfferingUnsupportedRoomCreation() {
        rule.setContent {
            CompanionTheme { ThreadsScreen(sessions = emptyList(), roomsAvailable = false) }
        }
        rule.mainClock.advanceTimeBy(500)
        capture("chat-list-empty-primary-action")
        rule.onNodeWithTag("threads.new").assertIsDisplayed()
        rule.onNodeWithTag("threads.new").assertTextContains("New chat")
        rule.onNodeWithTag("threads.newroom").assertDoesNotExist()
        rule.onNodeWithTag("threads.options").assertDoesNotExist()
    }
    @Test fun newChatIsPrimaryAndRoomsAreAnExplicitSecondaryAction() {
        var chats = 0
        var rooms = 0
        rule.setContent { CompanionTheme { ThreadsScreen(sessions = rows,
            onNew = { chats++ }, onNewRoom = { rooms++ }) } }
        rule.onNodeWithTag("threads.newroom").assertDoesNotExist()
        val action = rule.onNodeWithTag("threads.new").fetchSemanticsNode().boundsInRoot
        val filter = rule.onNodeWithTag("threads.filter").fetchSemanticsNode().boundsInRoot
        assertTrue(action.top > filter.bottom)
        rule.onNodeWithTag("threads.options").performClick()
        rule.onNodeWithText("New agent room").assertIsDisplayed().performClick()
        assertEquals(1, rooms); assertEquals(0, chats)
        rule.onNodeWithTag("threads.newroom").assertDoesNotExist()
        rule.onNodeWithTag("threads.new").performClick()
        assertEquals(1, chats); assertEquals(1, rooms)
        capture("chat-list-primary-action")
    }

    @Test fun finalConversationScrollsClearOfTheNewChatButton() {
        val many = (1..20).map { row("chat-$it", "Conversation $it", now - it * hour) }
        rule.setContent { CompanionTheme { ThreadsScreen(sessions = many) } }
        val viewport = rule.onNodeWithTag("threads.list").fetchSemanticsNode().boundsInRoot
        val rail = rule.onNodeWithTag("threads.rail").fetchSemanticsNode().boundsInRoot
        assertEquals("The list must continue beside the floating button, without a full-width empty footer",
            viewport.bottom, rail.bottom, 1f)
        rule.onNodeWithTag("threads.rail").performScrollToNode(hasTestTag("threads.row.chat-20"))
        rule.onNodeWithTag("threads.row.chat-20").assertIsDisplayed()
        val last = rule.onNodeWithTag("threads.row.chat-20").fetchSemanticsNode().boundsInRoot
        val action = rule.onNodeWithTag("threads.new").fetchSemanticsNode().boundsInRoot
        assertTrue("New chat must not cover the last conversation", last.bottom <= action.top)
        capture("chat-list-floating-action-scroll-clearance")
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val file = java.io.File("build/reports/threads-render/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun recoveringAnUnsentDraftIsSeparateFromCreatingAThread() {
        var resumed = 0
        var created = 0
        rule.setContent { CompanionTheme { ThreadsScreen(sessions = emptyList(), roomsAvailable = false,
            hasLocalDraft = true, onResumeLocalDraft = { resumed++ }, onNew = { created++ }) } }
        assertEquals(0, resumed); assertEquals(0, created)
        rule.onNodeWithTag("threads.resumeDraft").assertIsDisplayed().performClick()
        assertEquals(1, resumed); assertEquals(0, created)
        rule.onNodeWithTag("threads.new").performClick()
        assertEquals(1, created)
    }
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_788_868_800_000L // 2026-09-08T12:00Z

    private fun row(id: String, title: String, created: Long, updated: Long = created, count: Int = 0, source: String = "") =
        SessionRef(
            id = id,
            profileId = "coder",
            title = title,
            updatedAtEpochMs = updated,
            createdAtEpochMs = created,
            messageCount = count,
            source = source,
        )

    // Deliberately out of order: the screen must not depend on host order.
    private val rows = listOf(
        row("old", "last week's archive", created = now - 6 * day, count = 3, source = "telegram"),
        row("fresh", "gateway 502 on lab", created = now - hour, updated = now - hour, count = 12),
        row("busy", "old thread, active now", created = now - day - hour, updated = now - 60_000L, count = 40),
    )

    private fun tagsWithPrefix(prefix: String): List<String> =
        rule.onAllNodes(
            SemanticsMatcher("tag startsWith $prefix") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
            },
        ).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[SemanticsProperties.TestTag].removePrefix(prefix) }

    @Test
    fun createdSortGroupsByDayAndShowsCreationStamp() {
        val sorted = SessionLists.sort(rows, ThreadSort.CREATED)
        rule.setContent {
            CompanionTheme { ThreadsScreen(sessions = sorted, sort = ThreadSort.CREATED, nowMs = now) }
        }
        assertEquals(listOf("fresh", "busy", "old"), tagsWithPrefix("threads.row."))
        assertEquals(listOf("TODAY", "YESTERDAY", "THIS WEEK"), tagsWithPrefix("threads.group."))
        rule.onNodeWithTag("threads.time.fresh", useUnmergedTree = true).assertTextEquals("1h")
        rule.onNodeWithTag("threads.time.busy", useUnmergedTree = true).assertTextEquals("1d")
        rule.onNodeWithTag("threads.meta.old", useUnmergedTree = true).assertTextEquals("3 msgs · telegram")
        rule.onNodeWithTag("threads.count").assertTextEquals("3")
        rule.onNodeWithTag("threads.sort.menu").performClick()
        rule.onNodeWithTag("threads.sort.created").assertIsDisplayed()
    }

    @Test
    fun activeSortReordersAndShowsActivityStamp() {
        val sorted = SessionLists.sort(rows, ThreadSort.ACTIVE)
        rule.setContent {
            CompanionTheme { ThreadsScreen(sessions = sorted, sort = ThreadSort.ACTIVE, nowMs = now) }
        }
        assertEquals(listOf("busy", "fresh", "old"), tagsWithPrefix("threads.row."))
        rule.onNodeWithTag("threads.time.busy", useUnmergedTree = true).assertTextEquals("1m")
        assertEquals(listOf("TODAY", "THIS WEEK"), tagsWithPrefix("threads.group."))
    }

    @Test
    fun titleSortHasNoGroupHeaders() {
        val sorted = SessionLists.sort(rows, ThreadSort.TITLE)
        rule.setContent {
            CompanionTheme { ThreadsScreen(sessions = sorted, sort = ThreadSort.TITLE, nowMs = now) }
        }
        assertEquals(listOf("fresh", "old", "busy"), tagsWithPrefix("threads.row."))
        assertTrue(tagsWithPrefix("threads.group.").isEmpty())
    }

    @Test
    fun sortChipsReportTheChoice() {
        var picked: ThreadSort? = null
        rule.setContent {
            CompanionTheme { ThreadsScreen(sessions = rows, onSort = { picked = it }, nowMs = now) }
        }
        rule.onNodeWithTag("threads.sort.menu").performClick()
        rule.onNodeWithTag("threads.sort.active").performClick()
        assertEquals(ThreadSort.ACTIVE, picked)
        rule.onNodeWithTag("threads.sort.menu").performClick()
        rule.onNodeWithTag("threads.sort.title").performClick()
        assertEquals(ThreadSort.TITLE, picked)
    }

    @Test
    fun filterNarrowsRowsAndCount() {
        rule.setContent {
            CompanionTheme { ThreadsScreen(sessions = SessionLists.sort(rows), nowMs = now) }
        }
        rule.onNodeWithTag("threads.filter").performTextInput("gateway")
        assertEquals(listOf("fresh"), tagsWithPrefix("threads.row."))
        rule.onNodeWithTag("threads.count").assertTextEquals("1/3")
        rule.onNodeWithTag("threads.filter").performTextInput(" zzz")
        rule.onNodeWithTag("threads.nomatch").assertIsDisplayed()
    }

    @Test
    fun telegramChipFiltersLocallyAndArchivedChipReportsToHost() {
        var archived: Boolean? = null
        val withArchived = rows + row("arch", "old archived chat", created = now - 20 * day, source = "telegram").copy(archived = true)
        rule.setContent {
            CompanionTheme {
                ThreadsScreen(
                    sessions = SessionLists.sort(withArchived),
                    showArchived = true,
                    onToggleArchived = { archived = it },
                    nowMs = now,
                )
            }
        }
        rule.onNodeWithTag("threads.rail").performScrollToNode(hasTestTag("threads.row.arch"))
        rule.onNodeWithTag("threads.archived.arch", useUnmergedTree = true).assertTextEquals(" · ARCHIVED")
        rule.onNodeWithTag("threads.filter.menu").performClick()
        rule.onNodeWithTag("threads.filter.telegram").performClick()
        assertEquals(listOf("old", "arch"), tagsWithPrefix("threads.row."))
        rule.onNodeWithTag("threads.count").assertTextEquals("2/4")
        rule.onNodeWithTag("threads.filter.menu").performClick()
        rule.onNodeWithTag("threads.filter.telegram").performClick()
        rule.onNodeWithTag("threads.count").assertTextEquals("4")
        listOf("fresh", "busy", "old", "arch").forEach { id ->
            rule.onNodeWithTag("threads.rail").performScrollToNode(hasTestTag("threads.row.$id"))
            rule.onNodeWithTag("threads.row.$id").assertIsDisplayed()
        }
        rule.onNodeWithTag("threads.filter.menu").performClick()
        rule.onNodeWithTag("threads.filter.archived").performClick()
        assertEquals(false, archived)
    }

    @Test
    fun refreshCallsBackAndEmptyStateStillRenders() {
        var refreshed = 0
        rule.setContent {
            CompanionTheme { ThreadsScreen(sessions = emptyList(), onRefresh = { refreshed++ }, activeProfileId = "coder", nowMs = now) }
        }
        rule.onNodeWithTag("threads.empty").assertIsDisplayed()
        rule.onNodeWithTag("threads.refresh").performClick()
        assertEquals(1, refreshed)
    }
}
