package app.hermes.companion

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import app.hermes.companion.data.local.DraftOwner
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.*
import app.hermes.companion.settings.ComposerModelBottomSheet
import app.hermes.companion.settings.ComposerSettingsSection
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w411dp-h900dp-xhdpi")
class ComposerModelRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val choices = listOf(ModelOption("shared", "first", "Shared", reasoning = true, canDisableReasoning = true),
        ModelOption("shared", "second", "Shared", reasoning = true, canDisableReasoning = false))
    private fun panel() = SessionControlsPanel(DraftOwner("http://fixture", "fixture", "s"),
        SessionRef("s", "fixture", "Test", 0), 1, loading = false,
        settings = NativeSessionSettings("shared", "first", "none", "normal", choices))
    private fun mount(value: SessionControlsPanel = panel(), section: ComposerSettingsSection = ComposerSettingsSection.REASONING, onSet: (String, String, Boolean) -> Unit = { _, _, _ -> }) {
        rule.setContent { CompanionTheme { ComposerModelBottomSheet(value, onSet, {}, {}, initialSection = section) } }
        rule.mainClock.advanceTimeBy(500)
    }
    @Test fun providerFilterKeepsDuplicateModelNamesBoundToTheChosenProvider() {
        val changes = mutableListOf<String>()
        mount(section = ComposerSettingsSection.MODEL, onSet = { key, value, _ -> changes += "$key=$value" })
        rule.onNodeWithTag("composer.models.providers").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("composer.models.provider.second").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("composer.models.item.first/shared").assertDoesNotExist()
        rule.onNodeWithTag("composer.models.item.second/shared").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(listOf("model=shared --provider second --session"), changes)
    }
    @Test fun reasoningToggleUsesSessionMutationAndRequiredReasoningCannotBeDisabled() {
        val changes = mutableListOf<String>()
        mount(onSet = { key, value, _ -> changes += "$key=$value" })
        rule.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsOff().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(listOf("reasoning=medium"), changes)
    }
    @Test fun requiredReasoningAndPendingChangesDisableTheToggle() {
        val base = panel()
        mount(base.copy(settings = base.settings!!.copy(provider = "second", reasoning = "high")))
        rule.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsOn().assertIsNotEnabled()
    }
    @Test fun searchFiltersModelsWithoutChangingTheAppliedSelection() {
        mount(section = ComposerSettingsSection.MODEL)
        rule.onNodeWithTag("composer.models.search").performTextInput("missing model")
        rule.onNodeWithText("No matching models").assertExists()
        rule.onNodeWithTag("composer.tab.reasoning").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsOff()
    }
    @Test fun missingOptionalMetadataDoesNotBlockHostReasoningSettings() {
        val changes = mutableListOf<String>()
        val base = panel()
        mount(base.copy(settings = base.settings!!.copy(reasoning = "high", models = choices.map { it.copy(canDisableReasoning = null) })),
            onSet = { key, value, _ -> changes += "$key=$value" })
        rule.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsOn().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(listOf("reasoning=none"), changes)
        // No optimistic success: the switch keeps the last host-confirmed setting.
        rule.onNodeWithTag("composer.models.reasoning").assertIsOn()
    }
    @Test fun requiredReasoningStillAllowsEffortChangesAndBusyDisablesMutations() {
        val changes = mutableListOf<String>();val base = panel()
        mount(base.copy(settings = base.settings!!.copy(provider = "second", reasoning = "high")),
            onSet = { key, value, _ -> changes += "$key=$value" })
        rule.onNodeWithTag("composer.reasoning.low").performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(listOf("reasoning=low"), changes)
    }
    @Test fun anOmittedReasoningCapabilityIsNotTheSameAsExplicitlyUnsupported() {
        val changes = mutableListOf<String>();val base = panel()
        mount(base.copy(settings = base.settings!!.copy(reasoning = "none", models = choices.map {
            it.copy(reasoning = false, reasoningSupportReported = false, canDisableReasoning = null)
        })), onSet = { key, value, _ -> changes += "$key=$value" })
        rule.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(listOf("reasoning=medium"), changes)
    }
    @Test fun loadingOrBusyHostNeverAcceptsAnExtraToggle() {
        mount(panel().copy(busy = true))
        rule.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("composer.reasoning.high").performScrollTo().assertIsNotEnabled()
    }
}
