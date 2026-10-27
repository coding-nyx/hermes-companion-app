package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.*
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w360dp-h720dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotsScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val owner = BotOwner("https://fixture.invalid/hermes", "default")
    private val bots = listOf(NativeBot(owner, "Hermes", "Coordinator", model = "default-model", metadataRevision = 0),
        NativeBot(owner.copy(profile = "research"), "Research", "Research specialist", model = "research-model", metadataRevision = 0))
    private val group = NativeGroup("team", "Research team", listOf(NativeGroupMember("m", "research", "research", "Research")), "install", 1)
    private val caps = NativeGroupCapabilities(true, setOf("groups.send", "groups.create", "groups.approve", "groups.stop", "groups.rename", "groups.disband"))
    private var state by mutableStateOf(BotModeState(owner = owner, bots = bots, groups = listOf(group), capabilities = caps))
    private val actions = mutableListOf<String>()
    private fun mount() {
        compose.setContent { CompanionTheme {
            BotsScreen(state, "Lab", {}, { actions += "bot:${it.owner.profile}" }, { edit, _ -> actions += "save:${edit.profile}" },
                { name, members -> actions += "group:$name:${members.size}" }, { actions += "open-group" }, {},
                { state = state.copy(draft = it) }, { actions += "send:${state.draft}" }, { actions += "reply:$it" }, {}, {}, {}, {},
                { action, choice -> actions += "approval:${action.request}:$choice" }, {}, {}, {}, {}, Modifier.fillMaxSize(),
                onHostSwitch = { actions += "gateways" }, onChats = { actions += "chats" })
        } }
    }
    @Test fun rosterOpensTheCanonicalBotRatherThanSwitchingAnOrdinaryProfile() {
        mount(); compose.onNodeWithTag("bots.open.default").performClick()
        assertEquals(listOf("bot:default"), actions)
        capture("roster")
    }
    @Test fun createGroupRequiresAtLeastTwoGatewayMembers() {
        mount(); compose.onNodeWithTag("bots.new").performClick(); compose.onNodeWithTag("bots.group.create").performClick()
        compose.onNodeWithTag("bots.group.name").performTextInput("My team")
        compose.onNodeWithTag("bots.group.pick.default").performClick()
        compose.onNodeWithTag("bots.group.confirm").assertIsNotEnabled()
        compose.onNodeWithTag("bots.group.pick.research").performClick()
        capture("create-group", "bots.group.sheet")
        compose.onNodeWithTag("bots.group.confirm").performClick()
        assertEquals(listOf("group:My team:2"), actions)
    }
    @Test fun unsupportedHostCannotOfferNativeGroupCreation() {
        state = state.copy(capabilities = null, groupError = "Update host")
        mount(); compose.onNodeWithTag("bots.new").performClick(); compose.onNodeWithTag("bots.group.create").assertIsNotEnabled()
    }
    private fun checkStandaloneGateway(name: String) {
        state = BotModeState(owner = owner, unsupported = true,
            error = "This gateway uses Companion standalone. Bots and group chats need a native Hermes dashboard. Your existing conversations are available in Chats.")
        mount()
        compose.onNodeWithTag("bots.unsupported").assertIsDisplayed()
        compose.onNodeWithTag("bots.new").assertDoesNotExist()
        compose.onNodeWithTag("bots.search").assertDoesNotExist()
        compose.onNodeWithText("Create your first bot with +").assertDoesNotExist()
        compose.onNodeWithTag("bots.unsupported.chats").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("bots.unsupported.gateway").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("chats", "gateways"), actions)
        capture(name)
    }
    @Test fun standaloneGatewayExplainsTheLimitationAndOffersWorkingDestinations() = checkStandaloneGateway("standalone-dark")
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xhdpi")
    fun standaloneGatewayRemainsReadableInLightTheme() = checkStandaloneGateway("standalone-light")
    @Test fun standaloneGatewayActionsReflowAtLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        checkStandaloneGateway("standalone-large")
    }
    @Test fun groupCreationNeedsTwoBotsButNoTypedName() {
        mount(); compose.onNodeWithTag("bots.new").performClick(); compose.onNodeWithTag("bots.group.create").performClick()
        compose.onNodeWithTag("bots.group.confirm").assertIsNotEnabled()
        compose.onNodeWithTag("bots.group.pick.default").performClick()
        compose.onNodeWithTag("bots.group.confirm").assertIsNotEnabled()
        compose.onNodeWithTag("bots.group.pick.research").performClick()
        compose.onNodeWithTag("bots.group.confirm").assertIsEnabled()
        compose.onNodeWithText("Group name (optional)", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Hermes, Research", useUnmergedTree = true).assertIsDisplayed()
        capture("create-group-auto-name", "bots.group.sheet")
        compose.onNodeWithTag("bots.group.confirm").performClick()
        assertEquals(listOf("group::2"), actions)
    }
    @Test fun groupSendAndApprovalUseTheirExplicitControls() {
        val approval = NativeGroupAction("approval", "m", "task", 2, "approval-1", "Review command")
        state = state.copy(selected = NativeGroupSnapshot(group, actions = listOf(approval)), groupVerified = true,
            events = listOf(NativeGroupEvent("e", 1, "message.member", "Research", "A **research** result", "thread")), draft = "Follow up")
        mount()
        compose.onNodeWithContentDescription("Reply in thread").performClick()
        compose.onNodeWithText("Allow once").performClick()
        compose.onNodeWithTag("bots.group.send").performClick()
        assertEquals(listOf("reply:thread", "approval:approval-1:once", "send:Follow up"), actions)
        capture("group")
    }
    @Test fun moreOffersGatewaysAndPreservesAgentProfiles() {
        compose.setContent { CompanionTheme { NavigationMoreSheet(CompanionState(), { actions += it.name }, {}, {}) } }
        compose.onNodeWithTag("nav.gateway").performClick()
        assertEquals(listOf("GATEWAY"), actions)
    }
    @Test fun fullShellKeepsTheBotsScreenInsideTheExistingNavigationAndDarkPalette() {
        compose.setContent { CompanionTheme {
            CompanionShell(CompanionState(origin = owner.origin, activeProfileId = owner.profile, hostName = "Lab", tab = MainTab.BOTS, botMode = state),
                onOriginChange = {}, onConnect = {}, onSelectProfile = {}, onTab = {}, onOpenSession = {}, onNewThread = {},
                onCloseChat = {}, onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {}, imeVisible = false)
        } }
        compose.onNodeWithTag("bots.screen").assertIsDisplayed()
        compose.onNodeWithTag("toolbar.signature", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("nav.bots").assertIsSelected()
        capture("full-shell")
    }
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xhdpi") fun lightThemeUsesAConsistentPageBackground() {
        mount(); compose.onNodeWithTag("bots.open.default").assertIsDisplayed(); capture("roster-light")
    }
    @Test fun botCreationSheetKeepsTheAppsFieldsAndColorsAtLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
        mount(); compose.onNodeWithTag("bots.new").performClick(); compose.onNodeWithTag("bots.create").performClick()
        compose.onNodeWithTag("bots.editor.profile").performScrollTo().performTextInput("helper")
        compose.onNodeWithTag("bots.editor.title").performScrollTo().performTextInput("Helper")
        capture("create-bot-large", "bots.editor.sheet")
        compose.onNodeWithTag("bots.editor.save").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("save:helper"), actions)
    }
    @Test fun defaultLandingIsBotsAndDenseRosterShowsSixConversationsWithoutScrolling() {
        assertEquals(MainTab.BOTS, CompanionState().tab)
        state = state.copy(bots = (1..9).map { NativeBot(BotOwner(owner.origin, "bot-$it"), "Specialist $it", "A useful bot") }, groups = emptyList())
        compose.setContent { CompanionTheme {
            CompanionShell(CompanionState(origin = owner.origin, activeProfileId = owner.profile, hostName = "Lab", botMode = state),
                onOriginChange = {}, onConnect = {}, onSelectProfile = {}, onTab = {}, onOpenSession = {}, onNewThread = {},
                onCloseChat = {}, onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {}, imeVisible = false)
        } }
        compose.onNodeWithTag("nav.bots").assertIsSelected()
        (1..6).forEach { compose.onNodeWithTag("bots.open.bot-$it").assertIsDisplayed() }
        compose.onNodeWithTag("context.agent").assertDoesNotExist()
        capture("dense-shell")
    }
    @Test fun groupsFilterKeepsGroupConversationsVisibleWithoutBotRows() {
        mount(); compose.onNodeWithTag("bots.filter.groups").performClick()
        compose.onNodeWithTag("bots.group.team").assertIsDisplayed()
        compose.onNodeWithTag("bots.open.default").assertDoesNotExist()
    }
    @Test fun groupPickerKeepsCreateVisibleWithALongRosterAndLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
        state = state.copy(bots = (1..12).map { NativeBot(BotOwner(owner.origin, "bot-$it"), "Specialist $it", "A useful bot") })
        mount(); compose.onNodeWithTag("bots.new").performClick(); compose.onNodeWithTag("bots.group.create").performClick()
        compose.onNodeWithTag("bots.group.confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("bots.group.search").performScrollTo().performTextInput("Specialist 12")
        compose.onNodeWithTag("bots.group.pick.bot-12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("bots.group.confirm").assertIsDisplayed()
        capture("group-picker-large", "bots.group.sheet")
    }
    @Test fun searchUsesSymmetricMarginsAndOneCenteredTouchSizedField() {
        mount()
        compose.onNodeWithTag("bots.search").assertHeightIsEqualTo(48.dp).assertWidthIsEqualTo(328.dp)
        val field = compose.onNodeWithTag("bots.search").fetchSemanticsNode().boundsInRoot
        assertEquals(32f, field.left, 0.5f)
        compose.onNodeWithTag("bots.search").performTextInput("Research")
        compose.onNodeWithTag("bots.open.default").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("bots.open.default").assertIsDisplayed()
        capture("search-cleared")
    }
    @Test fun compactEditorKeepsLabelsAndSaveVisibleWithoutDuplicatePlaceholders() {
        mount(); compose.onNodeWithTag("bots.new").performClick(); compose.onNodeWithTag("bots.create").performClick()
        compose.onAllNodesWithText("Profile name", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("bots.editor.profile").assertHeightIsEqualTo(48.dp).performTextInput("helper")
        compose.onNodeWithTag("bots.editor.profile").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("bots.editor.title").assertHeightIsEqualTo(48.dp).performTextInput("Helper")
        compose.onNodeWithTag("bots.editor.title").assertHeightIsEqualTo(48.dp)
        compose.onAllNodesWithText("Profile name", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("bots.editor.save").assertIsDisplayed()
        val form = compose.onNodeWithTag("bots.editor.sheet").getUnclippedBoundsInRoot()
        assertTrue(form.bottom - form.top <= 440.dp)
        capture("create-bot-compact", "bots.editor.sheet")
    }
    private fun capture(name: String, tag: String? = null) {
        compose.waitForIdle()
        if (tag != null) compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (tag == null) view.draw(canvas) else {
                // Modal sheets live in another Window. Drawing only the Activity silently
                // captures the obscured page; PixelCopy is unavailable in this JVM renderer.
                val globalClass = Class.forName("android.view.WindowManagerGlobal")
                val global = globalClass.getDeclaredMethod("getInstance").invoke(null)
                val field = globalClass.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val roots = field.get(global) as List<android.view.View>
                check(roots.size > 1) { "No sheet window found for visual inspection" }
                roots.forEach { root ->
                    val position = IntArray(2); root.getLocationOnScreen(position)
                    canvas.save(); canvas.translate(position[0].toFloat(), position[1].toFloat()); root.draw(canvas); canvas.restore()
                }
            }
            File("/tmp/hermes-bots-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
