package com.pocketshell.uikit.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        // #2810: EVERY `type` role in tokens.json is bound by name to a
        // `PocketShellType` rung, the same shape #2800 gave the `size` table.
        // `bodyDense` / `bodyMono` / `labelMono` — 42 references across 23
        // files, including the ui-kit primitives themselves — used to live
        // only in Kotlin: in no token file and in no test. That gap is not
        // academic. The desktop client read the ladder off `Type.kt` source
        // rather than the token file and adopted the unpinned 13sp rung as
        // its body size (`--fs-300`), so the value bridging the two products
        // was the one nothing protected.
        //
        // The map is keyed by the JSON name so the key-set assertion below
        // fails BOTH ways: a rung added to `PocketShellType` without a
        // tokens.json entry reddens, and a tokens.json role with no Kotlin
        // binding reddens too.
        //
        // `workspace` and `terminal` are aliases (`= title`, `= metadata`),
        // never independent values — the two-copies failure #2630 shipped
        // with. They are bound here like any other role, which also brings
        // them under the weight assertion they previously escaped.
        val rungs = mapOf(
            "screen" to PocketShellType.screen,
            "workspace" to PocketShellType.workspace,
            "title" to PocketShellType.title,
            "body" to PocketShellType.body,
            "metadata" to PocketShellType.metadata,
            "label" to PocketShellType.label,
            "button" to PocketShellType.button,
            "terminal" to PocketShellType.terminal,
            "bodyDense" to PocketShellType.bodyDense,
            "bodyMono" to PocketShellType.bodyMono,
            "labelMono" to PocketShellType.labelMono,
            // #2812: the one new rung, mono/Medium/12sp for a key cap.
            "keycap" to PocketShellType.keycap,
        )
        val jsonTypeRoles = DesignKitTokens.root.getJSONObject("type").keys().asSequence().toSortedSet()
        assertEquals(
            "every tokens.json `type` role needs a PocketShellType binding by name (#2810) — " +
                "an unbound rung is a size waiting to drift, and a sibling product waiting to " +
                "derive its scale from our source code instead of our token file",
            jsonTypeRoles,
            rungs.keys.toSortedSet(),
        )
        rungs.forEach { (role, style) -> assertType(role, style) }

        // tokens.json carries size/lineHeight/weight but has no per-role font
        // family — the family lives in its top-level `font` block, one entry
        // for UI chrome and one for mono. So `bodyDense` and `bodyMono` are
        // byte-identical in the JSON (both 13/18/400) and the assertions above
        // cannot tell them apart: `assertType("bodyMono", bodyDense)` would
        // pass. Mono-ness is the whole point of a mono rung, so it is pinned
        // here in Kotlin space instead, including the discrimination the JSON
        // cannot express.
        assertEquals(JetBrainsMonoFamily, PocketShellType.bodyMono.fontFamily)
        assertEquals(JetBrainsMonoFamily, PocketShellType.labelMono.fontFamily)
        assertEquals(FontFamily.SansSerif, PocketShellType.bodyDense.fontFamily)
        assertNotEquals(
            "`bodyDense` and `bodyMono` share every number tokens.json records (13/18/400); " +
                "the font family is the only thing separating them, so losing it would make " +
                "the two rungs silently interchangeable",
            PocketShellType.bodyDense,
            PocketShellType.bodyMono,
        )

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
        // #2829 (3): every loop below walks tokens.json and looks the key up in
        // the generated theme, so a `PsTokens` val with NO key behind it passed
        // all of them silently — the exact residue a regeneration from a stale
        // JSON leaves behind (`spaceSection` had to be caught by a hand-written
        // one-off `assertFalse` precisely because no loop could see it). The
        // three set assertions here make the artifact's own declarations the
        // left-hand side, so an orphan val reddens by name.
        val sizeKeys = DesignKitTokens.root.getJSONObject("size").keys().asSequence().toSet()
        assertEquals(
            "the generated kit theme declares a `<n>.dp` val for every tokens.json `size`, " +
                "`space` and `radius` key and nothing else (#2829) — an extra val is a token " +
                "nothing generates and nothing pins",
            (sizeKeys + SPACE_TO_KIT_VAL.values + RADIUS_TO_KIT_VAL.values).toSortedSet(),
            DesignKitTokens.kitDpNames.toSortedSet(),
        )
        assertEquals(
            "the generated kit theme declares a `Color(0x…)` val for every tokens.json `color` " +
                "key and nothing else (#2829)",
            DesignKitTokens.root.getJSONObject("color").keys().asSequence().toSortedSet(),
            DesignKitTokens.kitColorNames.toSortedSet(),
        )

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
        SPACE_TO_KIT_VAL.forEach { (jsonKey, kitName) ->
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
        RADIUS_TO_KIT_VAL.forEach { (jsonKey, kitName) ->
            assertEquals(
                "kit theme radius `$kitName` drifted from tokens.json",
                DesignKitTokens.radiusDp(jsonKey),
                DesignKitTokens.kitDp(kitName),
            )
        }

        // Type — every `type` role against its `*Type` TextStyle, weight included.
        val kitTypeNames = TYPE_TO_KIT_VAL
        assertEquals(
            "the generated kit theme declares a `TextStyle` val for every `type` role it covers " +
                "and nothing else (#2829)",
            kitTypeNames.values.toSortedSet(),
            DesignKitTokens.kitTextStyleNames.toSortedSet(),
        )
        // #2810: the kit hand-off predates the app's own dense/mono rungs
        // (#461 Δ7/Δ8), so `PocketShellTheme.kt` declares no `bodyDenseType`
        // and this map cannot cover them. That exemption is spelled out as a
        // set rather than left as a silent omission, because a hand-kept list
        // that quietly ignores whatever it does not mention is exactly the
        // failure this issue is about. The assertion below therefore still
        // reddens when a NEW role lands in tokens.json: whoever adds it has
        // to say which side it belongs on.
        val rolesTheKitHandOffPredates = ROLES_THE_HAND_OFF_PREDATES
        assertEquals(
            "every tokens.json `type` role is either checked against the generated kit theme " +
                "or listed as one the kit hand-off predates (#2810)",
            DesignKitTokens.root.getJSONObject("type").keys().asSequence().toSortedSet(),
            (kitTypeNames.keys + rolesTheKitHandOffPredates).toSortedSet(),
        )
        kitTypeNames.forEach { (jsonRole, kitName) ->
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

    @Test
    fun generatedTokensCssStaysInSyncWithTokensJson() {
        // #2829 (4): `tokens.css` is the design kit's second generated hand-off
        // — the browser half of what `PocketShellTheme.kt` is the Android half
        // of — and until now nothing in this repo read it. No test parsed it,
        // no script checked it, so it could carry any numbers at all. It does
        // drift: at `0a3242e7e` the stylesheet was still missing `keycap`, a
        // role #2812 added to tokens.json AND to the Kotlin hand-off the same
        // day. One artifact regenerated, the other did not, and nothing said so.
        //
        // The pin is the same shape as the kit-theme one above: exhaustive per
        // key, with set assertions both ways so a `--var` with no token behind
        // it reddens too.

        // Which tokens.json blocks the stylesheet emits at all. Stated as two
        // sets rather than left implicit, so a NEW top-level block in the JSON
        // forces someone to say which side it belongs on instead of silently
        // landing on the omitted side.
        assertEquals(
            "every tokens.json top-level block is either pinned against tokens.css or named " +
                "as one the stylesheet does not emit (#2829)",
            DesignKitTokens.root.keys().asSequence().toSortedSet(),
            (BLOCKS_TOKENS_CSS_EMITS + BLOCKS_TOKENS_CSS_OMITS).toSortedSet(),
        )

        // Colours — the `color` block is emitted with no prefix (`--accent`),
        // and in CSS notation on both sides, so the strings compare directly.
        val colors = DesignKitTokens.root.getJSONObject("color")
        assertEquals(
            "tokens.css declares a custom property for every tokens.json `color` key and " +
                "nothing else (#2829)",
            colors.keys().asSequence().toSortedSet(),
            DesignKitTokens.cssBareVarNames.toSortedSet(),
        )
        colors.keys().asSequence().forEach { key ->
            assertEquals(
                "tokens.css colour `--$key` drifted from tokens.json",
                colors.getString(key).lowercase(),
                DesignKitTokens.cssVar(key).lowercase(),
            )
        }

        // Spacing rungs and density sizes — `<n>dp` in the JSON is `<n>px` here.
        mapOf("space" to "space", "size" to "size").forEach { (jsonBlock, cssPrefix) ->
            val keys = DesignKitTokens.root.getJSONObject(jsonBlock).keys().asSequence().toSortedSet()
            assertEquals(
                "tokens.css declares a `--$cssPrefix-*` property for every tokens.json " +
                    "`$jsonBlock` key and nothing else (#2829)",
                keys,
                DesignKitTokens.cssVarNames(cssPrefix).toSortedSet(),
            )
            keys.forEach { key ->
                assertEquals(
                    "tokens.css `--$cssPrefix-$key` drifted from tokens.json",
                    DesignKitTokens.root.getJSONObject(jsonBlock).getInt(key),
                    DesignKitTokens.cssPx("$cssPrefix-$key"),
                )
            }
        }

        // Type — size, leading and weight per role. The stylesheet predates the
        // app's own dense/mono rungs exactly as the Kotlin hand-off does, and
        // reuses that ONE exemption set rather than keeping a second copy: two
        // hand-written "roles we skip" lists is how the halves drift apart.
        assertEquals(
            "every tokens.json `type` role is either pinned against tokens.css or listed as " +
                "one the hand-off predates (#2829)",
            DesignKitTokens.root.getJSONObject("type").keys().asSequence().toSortedSet(),
            (DesignKitTokens.cssTypeRoles + ROLES_THE_HAND_OFF_PREDATES).toSortedSet(),
        )
        DesignKitTokens.cssTypeRoles.forEach { role ->
            assertEquals(
                "tokens.css `--type-$role-size` drifted from tokens.json",
                DesignKitTokens.typeSizeSp(role),
                DesignKitTokens.cssTypePx(role, "size"),
            )
            assertEquals(
                "tokens.css `--type-$role-leading` drifted from tokens.json",
                DesignKitTokens.typeLineHeightSp(role),
                DesignKitTokens.cssTypePx(role, "leading"),
            )
            assertEquals(
                "tokens.css `--type-$role-weight` drifted from tokens.json",
                DesignKitTokens.typeWeight(role),
                DesignKitTokens.cssTypeWeight(role),
            )
        }
    }

    @Test
    fun designSystemDocDensityTableListsEveryDensityRung() {
        // #2829 (1): `docs/design-system.md`'s density table is the human-facing
        // half of [PocketShellDensity], and the only half a designer or a new
        // implementer reads before reaching for a literal. #2800 bound four new
        // rungs in Kotlin — `icon`, `metadataIcon`, `buttonMin`, `screenGutter`
        // — and the table said nothing about three of them, so the documented
        // token vocabulary was a strict subset of the real one and the missing
        // members were precisely the ones whose absence causes a raw literal.
        //
        // Prose cannot be compiled, so nothing but a test keeps it honest.
        assertTrue(
            "no `val` rows parsed out of PocketShellDensity — this pin would be comparing two " +
                "empty sets and reporting perfection over nothing",
            DesignKitTokens.densityValNames.size >= 10,
        )
        assertEquals(
            "docs/design-system.md's `Density defaults` table must list every PocketShellDensity " +
                "rung, and only real rungs (#2829) — an undocumented token is a literal waiting " +
                "to be written, and a documented non-token is a name nobody can use",
            DesignKitTokens.densityValNames.toSortedSet(),
            DesignKitTokens.docDensityTableTokens.toSortedSet(),
        )
    }

    private companion object {
        /** tokens.json `space` key -> the generated kit theme's val name. */
        val SPACE_TO_KIT_VAL = mapOf(
            "xs" to "spaceXs",
            "sm" to "spaceSm",
            "md" to "spaceMd",
            "lg" to "spaceLg",
            "xl" to "spaceXl",
            "xxl" to "spaceXxl",
        )

        /** tokens.json `radius` key -> the generated kit theme's val name. */
        val RADIUS_TO_KIT_VAL = mapOf(
            "badge" to "badgeRadius",
            "chip" to "chipRadius",
            "field" to "fieldRadius",
            "button" to "buttonRadius",
            "card" to "cardRadius",
            "sheet" to "sheetRadius",
        )

        /** tokens.json `type` role -> the generated kit theme's `TextStyle` val name. */
        val TYPE_TO_KIT_VAL = mapOf(
            "screen" to "screenType",
            "workspace" to "workspaceType",
            "title" to "titleType",
            "body" to "bodyType",
            "metadata" to "metadataType",
            "label" to "labelType",
            "button" to "buttonType",
            "terminal" to "terminalType",
            "keycap" to "keycapType",
        )

        /**
         * The `type` roles BOTH generated hand-offs predate (#2810, #2829).
         *
         * `bodyDense`/`bodyMono`/`labelMono` are the app's own dense and mono
         * rungs (#461 Δ7/Δ8); neither `PocketShellTheme.kt` nor `tokens.css`
         * declares them, and the mono ones could not carry their defining
         * property — the font family — in either artifact anyway. One shared
         * set, not one per artifact: a second hand-written "roles we skip"
         * list is how the two halves of a hand-off drift apart in the first
         * place.
         */
        val ROLES_THE_HAND_OFF_PREDATES = setOf("bodyDense", "bodyMono", "labelMono")

        /** tokens.json blocks `tokens.css` emits as custom properties. */
        val BLOCKS_TOKENS_CSS_EMITS = setOf("color", "space", "size", "type")

        /**
         * tokens.json blocks `tokens.css` deliberately does not emit.
         *
         * `radius`/`motion` are real ladders the stylesheet has simply never
         * carried — the browser preview draws its own corners and transitions —
         * while `name`/`version`/`referenceViewport`/`font` are metadata, not
         * values a `:root` block could hold. Listed rather than skipped so a new
         * block in the JSON reddens [generatedTokensCssStaysInSyncWithTokensJson]
         * and someone has to decide which side it belongs on.
         */
        val BLOCKS_TOKENS_CSS_OMITS =
            setOf("name", "version", "referenceViewport", "radius", "motion", "font")
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
