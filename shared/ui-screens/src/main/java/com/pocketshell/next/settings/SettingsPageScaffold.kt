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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
            content = content,
        )
    }
}

@Composable
internal fun SettingsDescription(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellDensity.screenGutter),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Text(
            text = title,
            color = PocketShellColors.Text,
            style = PocketShellType.body,
        )
        Text(
            text = description,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.body,
        )
    }
}

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
            .padding(horizontal = PocketShellDensity.screenGutter),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = PocketShellColors.Text, style = PocketShellType.body)
                Text(text = description, color = PocketShellColors.TextSecondary, style = PocketShellType.body)
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
