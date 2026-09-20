package com.pocketshell.next.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlin.math.roundToInt

/**
 * Shared scaffold for the categorized Settings sub-pages (#2636 D6). Was
 * `private` in app2's `SettingsPages.kt`; `internal` here because every
 * consumer moved into this module with it.
 *
 * The categorized routes stay in app2 and call these screens.
 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    pageTag: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(pageTag),
    ) {
        SettingsHeader(title = title, onBack = onBack)
        // No `verticalArrangement` (#2804, #2635 audit P-3). Every row this
        // list holds paints its own divider, and a 12dp gap around that
        // hairline read as neither a separator nor a group break. The one
        // rhythm is divider-with-zero-gap (what Hosts already did); the blocks
        // that are NOT rows — [SettingsDescription], [SettingsSlider], the
        // page buttons — carry their own vertical padding instead, so the gap
        // is owned by the thing that needs it rather than sprayed between
        // every pair of items. `RowRhythmGuardTest` pins this list's shape.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            content = content,
        )
    }
}

/**
 * A settings block that is a title with one supporting line but no row
 * affordance.
 *
 * The supporting line is on the shared row grammar (#2804): the
 * [PocketShellType.metadata] rung in [PocketShellColors.TextMuted], the same
 * pairing `ListRow` and `QuietChoiceRow` use. It rendered
 * [PocketShellType.body] (14sp) on [PocketShellColors.TextSecondary] until
 * then — a third treatment for the same job, side by side with the other two
 * on one sub-page.
 *
 * The vertical padding is this block's own, since the enclosing scaffold no
 * longer spaces its items (see [SettingsPageScaffold]).
 */
@Composable
internal fun SettingsDescription(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PocketShellDensity.screenGutter,
                vertical = PocketShellSpacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Text(
            text = title,
            color = PocketShellColors.Text,
            style = PocketShellType.body,
        )
        Text(
            text = description,
            color = PocketShellColors.TextMuted,
            style = PocketShellType.metadata,
        )
    }
}

/**
 * A settings block whose title + supporting line sit above a [Slider].
 *
 * Supporting line and vertical padding follow [SettingsDescription] — see its
 * KDoc for why both moved in #2804.
 */
@Composable
internal fun SettingsSlider(
    title: String,
    description: String,
    value: Float,
    valueLabel: String,
    min: Float,
    max: Float,
    step: Float,
    onChange: (Float) -> Unit,
    sliderTestTag: String,
    valueTestTag: String,
) {
    val clampedValue = value.coerceIn(min, max)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PocketShellDensity.screenGutter,
                vertical = PocketShellSpacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = PocketShellColors.Text, style = PocketShellType.body)
                // Same supporting-line rung and colour as SettingsDescription
                // and the shared rows (#2804).
                Text(text = description, color = PocketShellColors.TextMuted, style = PocketShellType.metadata)
            }
            Text(
                text = valueLabel,
                color = PocketShellColors.Text,
                style = PocketShellType.metadata,
                modifier = Modifier.testTag(valueTestTag),
            )
        }
        Slider(
            value = clampedValue,
            onValueChange = { raw ->
                val steps = ((raw - min) / step).roundToInt()
                onChange((min + steps * step).coerceIn(min, max))
            },
            valueRange = min..max,
            steps = ((max - min) / step).roundToInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = PocketShellColors.Text,
                activeTrackColor = PocketShellColors.TextSecondary,
                inactiveTrackColor = PocketShellColors.Border,
            ),
            modifier = Modifier
                .testTag(sliderTestTag)
                .semantics {
                    contentDescription = title
                    stateDescription = valueLabel
                },
        )
    }
}
