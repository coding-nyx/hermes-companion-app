package app.hermes.companion.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A single toolbar owns the title, connection context and actions on every main page. */
@Composable
fun PageToolbar(title: String, subtitle: String, modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier, onTitleClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Column(titleModifier.weight(1f).heightIn(min = 48.dp)
            .then(if (onTitleClick != null) Modifier.clickable(role = Role.Button, onClick = onTitleClick) else Modifier),
            verticalArrangement = Arrangement.Center) {
            Text(title, style = CompanionType.Display.copy(fontSize = 20.sp, lineHeight = 26.sp),
                maxLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                if (leading == null) SignalSignature(label = subtitle, modifier = Modifier.fillMaxWidth().testTag("toolbar.signature"))
                else Text(subtitle, style = CompanionType.MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}
