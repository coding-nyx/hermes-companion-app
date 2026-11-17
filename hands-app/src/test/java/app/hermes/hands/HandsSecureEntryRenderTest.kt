package app.hermes.hands

import android.app.Application
import android.util.SparseArray
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.hermes.companion.design.CompanionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31], qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HandsSecureEntryRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w360dp-h720dp-night-xhdpi")
    fun darkPrivateInputDoesNotExposeValueToSemanticsAccessibilityOrSavedState() {
        var input: EditText? = null
        rule.setContent { CompanionTheme {
            SecureEntryScreen("request-1", "pending", "com.example.profile", "Postal address", true,
                { _, _ -> false }, {}, {}, {}, { input = it })
        } }
        rule.runOnIdle {
            val field = requireNotNull(input)
            field.id = 1234
            field.setText("DUMMY-PII-1049")
            assertFalse(field.transformationMethod.getTransformation(field.text, field).toString().contains("DUMMY-PII-1049"))
            assertFalse(field.isSaveEnabled)
            assertFalse(field.isSaveFromParentEnabled)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, field.importantForAccessibility)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
            assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, field.importantForContentCapture)
            assertTrue(field.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
            assertTrue(field.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertNull(field.createAccessibilityNodeInfo().text)
            val saved = SparseArray<android.os.Parcelable>()
            field.saveHierarchyState(saved)
            assertEquals(0, saved.size())
        }
        rule.onAllNodesWithText("DUMMY-PII-1049", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("privateEntry.input").performScrollTo().assertIsDisplayed()
        capture("secure-entry-dark")
    }

    @Test fun privateFieldSupportsAccessibilityFocusWithoutExposingValueInEventsOrLabel() {
        var input: EditText? = null
        rule.setContent { CompanionTheme {
            SecureEntryScreen("request-1", "pending", "com.example.profile", "Postal address", true,
                { _, _ -> false }, {}, {}, {}, { input = it })
        } }
        rule.runOnIdle {
            val field = requireNotNull(input)
            field.setText("DUMMY-PII-1049")
            val info = field.createAccessibilityNodeInfo()
            assertTrue(info.isFocusable)
            assertTrue(info.isEditable)
            assertTrue(info.isPassword)
            assertEquals("Private value", info.contentDescription)
            assertEquals("Private value", info.hintText)
            assertNull(info.text)
            var delivered = 0
            field.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
                    delivered++
                    assertTrue(event.text.isEmpty())
                    assertNull(event.beforeText)
                    assertEquals("Private value", event.contentDescription)
                    assertEquals(-1, event.fromIndex)
                    assertEquals(0, event.itemCount)
                }
            }
            val focused = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply {
                text.add("DUMMY-PII-1049")
                beforeText = "DUMMY-PII-1049"
                contentDescription = "DUMMY-PII-1049"
            }
            field.sendAccessibilityEventUnchecked(focused)
            assertEquals(1, delivered)
            field.sendAccessibilityEventUnchecked(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED).apply {
                text.add("DUMMY-PII-1049")
            })
            field.sendAccessibilityEventUnchecked(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED).apply {
                text.add("DUMMY-PII-1049")
            })
            assertEquals(1, delivered)
        }
    }

    @Test fun fillingClearsLocalInputAndEndingRequiresExplicitAcknowledgement() {
        val status = mutableStateOf("pending")
        var input: EditText? = null
        var fills = 0
        var releases = 0
        rule.setContent { CompanionTheme {
            SecureEntryScreen("request-1", status.value, "com.example.profile", "Postal address", true,
                onSubmit = { id, value ->
                    assertEquals("request-1", id)
                    assertEquals("DUMMY-PII-1049", value)
                    fills++
                    status.value = "filled"
                    true
                }, onCancel = {}, onRelease = { releases++ }, onLeave = {}, onInputReady = { input = it })
        } }
        val native = rule.runOnIdle { requireNotNull(input).apply { setText("DUMMY-PII-1049") } }
        rule.onNodeWithTag("privateEntry.fill").performScrollTo().performClick()
        assertEquals(1, fills)
        assertEquals(0, native.length())
        rule.onAllNodesWithText("DUMMY-PII-1049", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("privateEntry.end").performScrollTo().assertIsNotEnabled()
        assertEquals(0, releases)
        rule.onNodeWithTag("privateEntry.acknowledge").performScrollTo().performClick()
        rule.onNodeWithTag("privateEntry.end").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, releases)
        capture("secure-entry-light-filled")
    }

    @Test fun cancelWipesInputAndKeepsRemoteAccessPaused() {
        var input: EditText? = null
        var canceled: String? = null
        var releases = 0
        rule.setContent { CompanionTheme {
            SecureEntryScreen("request-1", "pending", "com.example.profile", "Postal address", true,
                { _, _ -> fail("Cancel must never submit a value"); false }, { canceled = it }, { releases++ }, {}, { input = it })
        } }
        rule.runOnIdle { requireNotNull(input).setText("DUMMY-PII-1049") }
        rule.onNodeWithTag("privateEntry.cancel").performScrollTo().performClick()
        assertEquals("request-1", canceled)
        assertEquals(0, input?.length())
        assertEquals(0, releases)
    }

    @Test fun largeTextPendingFormAndRecoveryRemainScrollable() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        val status = mutableStateOf("pending")
        rule.setContent { CompanionTheme {
            SecureEntryScreen("request-1", status.value, "com.example.profile.longpackagename", "A long field label for a private address", true,
                { _, _ -> false }, { status.value = "cancelled" }, {}, {})
        } }
        rule.onNodeWithTag("privateEntry.input").performScrollTo().assertIsDisplayed()
        capture("secure-entry-large-text-pending")
        rule.onNodeWithTag("privateEntry.fill").performScrollTo().assertIsDisplayed()
        capture("secure-entry-large-text-actions")
        rule.onNodeWithTag("privateEntry.cancel").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("privateEntry.acknowledge").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("privateEntry.end").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("secure-entry-large-text-recovery")
    }

    @Test @Config(qualifiers = "w360dp-h720dp-night-xhdpi")
    fun darkFailedRecoveryCanBeLeftWithoutReleasingPrivacy() {
        var leaves = 0
        var releases = 0
        rule.setContent { CompanionTheme {
            SecureEntryScreen("request-1", "failed", "com.example.profile", "Postal address", true,
                { _, _ -> false }, {}, { releases++ }, { leaves++ })
        } }
        rule.onNodeWithTag("privateEntry.end").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("privateEntry.leave").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, leaves)
        assertEquals(0, releases)
        capture("secure-entry-dark-recovery")
    }

    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val file = java.io.File("build/reports/hands-private-entry-render/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
