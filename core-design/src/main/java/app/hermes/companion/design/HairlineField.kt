package app.hermes.companion.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single-line hairline input. Keyboard behaviour is decided here so every screen agrees (A18.4):
 * - URL / package / password fields get no autocorrect and no auto-capitalisation
 * - focusing scrolls the field above the IME (works inside `verticalScroll` parents)
 * - Done / Send / Go run [onDone] and then drop focus + hide the keyboard unless [keepKeyboardOnDone]
 * - Next runs [onNext] (default: move focus forward)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HairlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Uri,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    placeholder: String? = null,
    focusRequester: FocusRequester? = null,
    keepKeyboardOnDone: Boolean = false,
    onNext: (() -> Unit)? = null,
    onDone: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val freeform = keyboardType == KeyboardType.Text
    fun submit() {
        onDone()
        if (!keepKeyboardOnDone) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    // Let the IME inset start animating, then pull the field above it.
                    scope.launch {
                        delay(120)
                        requester.bringIntoView()
                    }
                }
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .border(CompanionSpace.Hairline, CompanionColor.LineStrong)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        textStyle = CompanionType.Mono.copy(color = CompanionColor.Text),
        cursorBrush = SolidColor(CompanionColor.Signal),
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(
            capitalization = if (freeform) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = freeform,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { submit() },
            onSend = { submit() },
            onGo = { submit() },
            onSearch = { submit() },
            onNext = { onNext?.invoke() ?: focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) },
        ),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                    Text(
                        text = placeholder,
                        style = CompanionType.Mono.copy(color = CompanionColor.TextMute),
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

/** Drop focus and hide the IME — call from buttons that submit a form. */
@Composable
fun rememberDismissKeyboard(): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return {
        keyboard?.hide()
        focus.clearFocus()
    }
}
