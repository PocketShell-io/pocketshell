package com.pocketshell.uikit.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Contract checks pinning the PocketShell theme to the design kit's single
 * machine-readable token source (#2717).
 *
 * Every pin READS `docs/design-kit/design-system/tokens.json` through
 * [DesignKitTokens] instead of restating hand-copied constants: mutating a
 * value in the JSON (e.g. flipping `type.screen.sizeSp` back to 28, the #2630
 * drift) now turns the type-scale, row-height and kit-theme-sync tests red in
 * the normal shared ui-kit JVM gate.
 *
 * The kit-theme-sync test additionally parses the *generated*
 * `docs/design-kit/android/PocketShellTheme.kt`, so regenerating the kit
 * artifacts from a stale or hand-edited JSON cannot silently reintroduce old
 * numbers (the exact trap #2717 closed: the committed kit theme still carried
 * 28sp after #2630 reconciled the scale).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class QuietThemeTokenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun quietColorTokensMatchTheDesignKit() {
        assertColor("background", PocketShellColors.Background)
        assertColor("surface", PocketShellColors.Surface)
        assertColor("surfaceRaised", PocketShellColors.SurfaceElev)
        assertColor("text", PocketShellColors.Text)
        assertColor("secondary", PocketShellColors.TextSecondary)
        assertColor("muted", PocketShellColors.TextMuted)
        assertColor("divider", PocketShellColors.BorderSoft)
        assertColor("inputBorder", PocketShellColors.Border)
        assertColor("accent", PocketShellColors.Accent)
        assertColor("onAccent", PocketShellColors.OnAccent)
        assertColor("positive", PocketShellColors.Green)
        assertColor("warning", PocketShellColors.Amber)
        assertColor("error", PocketShellColors.Red)
        assertColor("terminal", PocketShellColors.TermBg)
        assertEquals(PocketShellColors.Text, PocketShellColors.TermText)
        assertEquals(PocketShellColors.Accent, PocketShellColors.TermPrompt)
        assertEquals(PocketShellColors.TextMuted, PocketShellColors.TermComment)
        assertColor("scrim", PocketShellColors.Scrim)
    }

    @Test
    fun quietShapeTokensMatchTheDesignKit() {
        assertEquals(DesignKitTokens.radiusDp("field").dp, topStartRadius(PocketShellShapes.small).dp)
        assertEquals(DesignKitTokens.radiusDp("button").dp, topStartRadius(PocketShellShapes.medium).dp)
        assertEquals(DesignKitTokens.radiusDp("sheet").dp, topStartRadius(PocketShellShapes.large).dp)
    }

    @Test
    fun typeScaleMatchesTheDesignKit() {
        // The named rungs — one assertion per tokens.json `type` role.
        assertType("screen", PocketShellType.screen)
        assertType("title", PocketShellType.title)
        assertType("body", PocketShellType.body)
        assertType("metadata", PocketShellType.metadata)
        assertType("label", PocketShellType.label)
        assertType("button", PocketShellType.button)
        // `workspace` and `terminal` are aliases, never independent values —
        // the two-copies failure #2630 shipped with.
        assertEquals(DesignKitTokens.typeSizeSp("workspace").sp, PocketShellType.workspace.fontSize)
        assertEquals(DesignKitTokens.typeLineHeightSp("workspace").sp, PocketShellType.workspace.lineHeight)
        assertEquals(DesignKitTokens.typeSizeSp("terminal").sp, PocketShellType.terminal.fontSize)
        assertEquals(DesignKitTokens.typeLineHeightSp("terminal").sp, PocketShellType.terminal.lineHeight)

        // The `quiet*` spellings are the same instances, not re-declared copies.
        assertEquals(PocketShellType.screen, PocketShellType.quietScreen)
        assertEquals(PocketShellType.title, PocketShellType.quietTitle)
        assertEquals(PocketShellType.body, PocketShellType.quietBody)
        assertEquals(PocketShellType.metadata, PocketShellType.quietMetadata)
        assertEquals(PocketShellType.label, PocketShellType.quietLabel)

        // The M3 slots the app actually reads.
        assertType("screen", PocketShellTypography.headlineSmall)
        assertType("title", PocketShellTypography.titleMedium)
        assertType("body", PocketShellTypography.bodyMedium)
        assertType("label", PocketShellTypography.labelSmall)
    }

    @Test
    fun rowHeightsMatchTheDesignKit() {
        // #2800: EVERY `size` key in tokens.json is bound by name in
        // PocketShellDensity. Six of ten used to have no Kotlin binding at
        // all, so call sites restated them as `18.dp`/`24.dp` literals that
        // nothing pinned. The map is keyed by the JSON name so the key-set
        // assertion below fails both ways: a binding removed here (or in
        // `PocketShellDensity`) reddens, and a key added to tokens.json with
        // no binding reddens too.
        val bindings = mapOf(
            "touchMin" to PocketShellDensity.tapTargetMin,
            "buttonMin" to PocketShellDensity.buttonMin,
            "fieldMin" to PocketShellDensity.fieldMin,
            "workspaceRowMin" to PocketShellDensity.workspaceRowMinHeight,
            "listRowMin" to PocketShellDensity.rowMinHeight,
            "icon" to PocketShellDensity.icon,
            "metadataIcon" to PocketShellDensity.metadataIcon,
            "screenGutter" to PocketShellDensity.screenGutter,
        )
        val jsonSizeKeys = DesignKitTokens.root.getJSONObject("size").keys().asSequence().toSortedSet()
        assertEquals(
            "every tokens.json `size` key needs a PocketShellDensity binding by name (#2800) — " +
                "an unbound key is a literal waiting to drift",
            jsonSizeKeys,
            bindings.keys.toSortedSet(),
        )
        bindings.forEach { (key, bound) ->
            assertEquals(
                "PocketShellDensity binding for tokens.json `size.$key` drifted",
                DesignKitTokens.sizeDp(key).dp,
                bound,
            )
        }
        // `standardRowMinHeight` is an alias, not a second value (#2630's drift class).
        assertEquals(PocketShellDensity.rowMinHeight, PocketShellDensity.standardRowMinHeight)
        // #2717 T3: the 32dp `section` rung is retired; sections separate with
        // `sectionGap`, pinned to the surviving 24dp `space.xxl` rung.
        assertEquals(DesignKitTokens.spaceDp("xxl").dp, PocketShellDensity.sectionGap)
    }

    @Test
    fun generatedKitThemeStaysInSyncWithTokensJson() {
        // Colors — the kit theme's `PsTokens` vals carry the same names.
        val colors = DesignKitTokens.root.getJSONObject("color")
        colors.keys().asSequence().forEach { key ->
            assertEquals(
                "kit theme color `$key` drifted from tokens.json",
                DesignKitTokens.colorArgb(key),
                DesignKitTokens.kitColorArgb(key),
            )
        }

        // Sizes — same-name vals (`workspaceRowMin`, `listRowMin`, ...).
        val sizes = DesignKitTokens.root.getJSONObject("size")
        sizes.keys().asSequence().forEach { key ->
            assertEquals(
                "kit theme size `$key` drifted from tokens.json",
                DesignKitTokens.sizeDp(key),
                DesignKitTokens.kitDp(key),
            )
        }

        // Spaces — `xs` -> `spaceXs`, etc. The retired 32dp `section` rung must
        // NOT come back in a regeneration.
        mapOf(
            "xs" to "spaceXs",
            "sm" to "spaceSm",
            "md" to "spaceMd",
            "lg" to "spaceLg",
            "xl" to "spaceXl",
            "xxl" to "spaceXxl",
        ).forEach { (jsonKey, kitName) ->
            assertEquals(
                "kit theme space `$kitName` drifted from tokens.json",
                DesignKitTokens.spaceDp(jsonKey),
                DesignKitTokens.kitDp(kitName),
            )
        }
        assertFalse(
            "tokens.json has no `space.section` rung (#2717 T3) — the regenerated " +
                "kit theme must not re-emit `spaceSection`",
            DesignKitTokens.kitThemeText.contains("spaceSection"),
        )

        // Radius ladder — {4 badge, 8 chip, 12 field/button/card, 24 sheet}.
        mapOf(
            "badge" to "badgeRadius",
            "chip" to "chipRadius",
            "field" to "fieldRadius",
            "button" to "buttonRadius",
            "card" to "cardRadius",
            "sheet" to "sheetRadius",
        ).forEach { (jsonKey, kitName) ->
            assertEquals(
                "kit theme radius `$kitName` drifted from tokens.json",
                DesignKitTokens.radiusDp(jsonKey),
                DesignKitTokens.kitDp(kitName),
            )
        }

        // Type — every `type` role against its `*Type` TextStyle, weight included.
        mapOf(
            "screen" to "screenType",
            "workspace" to "workspaceType",
            "title" to "titleType",
            "body" to "bodyType",
            "metadata" to "metadataType",
            "label" to "labelType",
            "button" to "buttonType",
            "terminal" to "terminalType",
        ).forEach { (jsonRole, kitName) ->
            val (kitSize, kitLineHeight) = DesignKitTokens.kitType(kitName)
            assertEquals(
                "kit theme `$kitName` size drifted from tokens.json",
                DesignKitTokens.typeSizeSp(jsonRole),
                kitSize,
            )
            assertEquals(
                "kit theme `$kitName` line height drifted from tokens.json",
                DesignKitTokens.typeLineHeightSp(jsonRole),
                kitLineHeight,
            )
            assertEquals(
                "kit theme `$kitName` weight drifted from tokens.json",
                DesignKitTokens.typeWeight(jsonRole),
                DesignKitTokens.kitTypeWeight(kitName),
            )
        }
    }

    @Test
    fun materialThemeMapsQuietRolesAndPreservesTheTerminalInputs() {
        var scheme: ColorScheme? = null
        composeRule.setContent {
            PocketShellTheme {
                scheme = MaterialTheme.colorScheme
            }
        }

        composeRule.runOnIdle {
            val mapped = requireNotNull(scheme)
            assertEquals(PocketShellColors.Background, mapped.background)
            assertEquals(PocketShellColors.Surface, mapped.surface)
            assertEquals(PocketShellColors.SurfaceElev, mapped.surfaceVariant)
            assertEquals(PocketShellColors.Accent, mapped.primary)
            assertEquals(PocketShellColors.OnAccent, mapped.onPrimary)
            assertEquals(PocketShellColors.SurfaceElev, mapped.primaryContainer)
            assertEquals(PocketShellColors.TextSecondary, mapped.secondary)
            assertEquals(PocketShellColors.Border, mapped.outline)
            assertEquals(PocketShellColors.BorderSoft, mapped.outlineVariant)
            assertEquals(PocketShellColors.Red, mapped.error)
            assertEquals(PocketShellColors.Background, mapped.onError)
            assertEquals(PocketShellColors.Scrim, mapped.scrim)
        }
    }

    private fun assertColor(jsonKey: String, actual: Color) {
        assertEquals(
            "PocketShellColors token drifted from tokens.json `$jsonKey`",
            Color(DesignKitTokens.colorArgb(jsonKey).toInt()),
            actual,
        )
    }

    private fun assertType(jsonRole: String, style: androidx.compose.ui.text.TextStyle) {
        assertEquals(
            "type rung `$jsonRole` size drifted from tokens.json",
            DesignKitTokens.typeSizeSp(jsonRole).sp,
            style.fontSize,
        )
        assertEquals(
            "type rung `$jsonRole` line height drifted from tokens.json",
            DesignKitTokens.typeLineHeightSp(jsonRole).sp,
            style.lineHeight,
        )
        assertEquals(
            "type rung `$jsonRole` weight drifted from tokens.json",
            FontWeight(DesignKitTokens.typeWeight(jsonRole)),
            style.fontWeight,
        )
    }

    private fun topStartRadius(shape: Shape): Float {
        val outline = shape.createOutline(
            size = Size(100f, 100f),
            layoutDirection = LayoutDirection.Ltr,
            density = Density(1f),
        )
        return (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x
    }
}
