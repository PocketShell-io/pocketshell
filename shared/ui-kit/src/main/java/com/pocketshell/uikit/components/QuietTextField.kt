package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

/** The quiet field radius — the same 12dp rung the composer pills use. */
private val QuietFieldShape = RoundedCornerShape(12.dp)

/**
 * The kit's one text field (issue #2635). Replaces the hand-declared
 * `OutlinedTextField` colour blocks screens used to re-declare per sheet:
 *
 * - the label sits ABOVE the field on the [PocketShellType.label] rung — no
 *   floating label mid-animation;
 * - the field is a filled [Surface] on the 12dp [QuietFieldShape], outline on
 *   [PocketShellColors.Border], turning [PocketShellColors.Accent] while the
 *   field holds focus and the semantic error colour when [isError];
 * - the height honours [PocketShellDensity.fieldMinHeight] through the content
 *   padding, so a field in a form column lines up with 56dp rows and buttons.
 *
 * Presentational only: all state lives in the caller's `value`.
 */
@Composable
fun QuietTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = PocketShellType.body,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
) {
    val semantic = LocalPocketShellSemantic.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val outlineColor = when {
        isError -> semantic.statusError
        focused -> PocketShellColors.Accent
        else -> PocketShellColors.Border
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.label,
        )
        Surface(
            color = PocketShellColors.SurfaceElev,
            contentColor = PocketShellColors.Text,
            shape = QuietFieldShape,
            border = BorderStroke(1.dp, outlineColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = textStyle.copy(color = PocketShellColors.Text),
                cursorBrush = SolidColor(PocketShellColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = keyboardActions,
                interactionSource = interactionSource,
                modifier = Modifier
                    .heightIn(min = PocketShellDensity.fieldMinHeight)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            color = PocketShellColors.TextMuted,
                            style = textStyle,
                            maxLines = 1,
                        )
                    } else {
                        inner()
                    }
                },
            )
        }
        supportingText?.invoke()
    }
}
