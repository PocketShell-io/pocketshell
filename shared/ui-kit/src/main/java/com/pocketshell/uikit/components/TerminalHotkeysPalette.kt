package com.pocketshell.uikit.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import kotlin.math.roundToInt

/**
 * A labelled group of hotkeys.
 *
 * [rows] normally follows [columns], while the Ctrl picker supplies explicit
 * QWERTY rows so `HJKL` and `NM\` do not flow into neighbouring rows.
 */
data class HotkeySection(
    val title: String,
    val keys: List<KeyBinding>,
    val columns: Int,
    val rows: List<List<KeyBinding>> = keys.chunked(columns),
)

/** Transient page selected inside the terminal hotkeys palette. */
enum class TerminalHotkeysPage {
    Main,
    Ctrl,
}

/** A discoverable alternate action attached to one key target. */
data class HotkeyLongPressAction(
    val cue: String,
    val accessibilityLabel: String,
)

const val TERMINAL_HOTKEYS_PALETTE_TAG: String = "terminal:hotkeys-palette"
const val TERMINAL_HOTKEYS_PALETTE_CLOSE_TAG: String = "terminal:hotkeys-palette-close"
const val TERMINAL_HOTKEYS_PALETTE_BACK_TAG: String = "terminal:hotkeys-palette-back"
const val TERMINAL_HOTKEYS_PALETTE_HANDLE_TAG: String = "terminal:hotkeys-palette-handle"
const val TERMINAL_HOTKEYS_CTRL_FLOW_TAG: String = "terminal:hotkeys-ctrl-flow"

/**
 * Test-readable overflow signal. Compose otherwise clips a label while its
 * semantics bounds still appear contained.
 */
val HotkeyLabelTruncatedKey: SemanticsPropertyKey<Boolean> =
    SemanticsPropertyKey("HotkeyLabelTruncated")
var SemanticsPropertyReceiver.hotkeyLabelTruncated: Boolean by HotkeyLabelTruncatedKey

/** Fixed width of the floating palette card. Compact by design (#2612). */
private val PaletteWidth: Dp = 300.dp

/** Inset the palette keeps from the container's bottom-end corner by default. */
private val PaletteInset: Dp = PocketShellSpacing.lg

/** The floating card's corner radius — the design system's 14dp card rung. */
private val PaletteShape = RoundedCornerShape(14.dp)

/**
 * Clamp a palette pull so the card stays fully inside its container.
 *
 * The pull is expressed in the BOTTOM-END coordinate system the card is
 * laid out in: `(0, 0)` places the card flush with the container's
 * bottom-end corner, and negative components pull it left (`x`) / up (`y`).
 * The clamp bounds are therefore `[-(container - palette), 0]` per axis,
 * floored at zero for a container smaller than the card.
 *
 * Because the APPLIED offset is re-derived from the stored pull on every
 * layout pass, the same function is also what reconciles the position when
 * the viewport changes (orientation, split screen, an IME policy that
 * resizes the window): the stored pull is never trusted raw.
 *
 * Pure function — no Android types beyond Compose value classes — so the
 * bounds contract is unit-pinned without a device.
 */
fun clampPalettePull(
    pull: Offset,
    paletteSize: IntSize,
    containerSize: IntSize,
): Offset {
    val maxX = (containerSize.width - paletteSize.width).coerceAtLeast(0)
    val maxY = (containerSize.height - paletteSize.height).coerceAtLeast(0)
    val x = pull.x.coerceIn(-maxX.toFloat(), 0f)
    val y = pull.y.coerceIn(-maxY.toFloat(), 0f)
    // When the free space is 0 the clamp bound is -0f; a -0f pull is
    // numerically but not bitwise 0f, which breaks Offset equality.
    return Offset(if (x == 0f) 0f else x, if (y == 0f) 0f else y)
}

/**
 * The compact, draggable, NON-modal terminal hotkeys palette (#2612).
 *
 * Issue #2612 replaces the #2521 full-width `ModalBottomSheet` — it covered
 * a large part of the terminal, forcing an open/navigate/close/inspect loop —
 * with a small floating card:
 *
 * - It floats OVER the terminal from inside the terminal's own layout slot;
 *   it never resizes the terminal, dims it, or changes `stty size`.
 * - There is no scrim and no dismiss-on-outside-tap: the terminal stays
 *   visible and interactive, and the palette stays open across key taps.
 * - It is moved ONLY by its dedicated drag handle (the header row next to
 *   the close button). The handle shares no surface with a key, so a drag
 *   can never leak a keystroke to the PTY.
 * - Its position is stored as a bottom-end "pull" and re-clamped by
 *   [clampPalettePull] on every layout, so it cannot be dragged or resized
 *   out of the visible container.
 *
 * Page state (Main / Ctrl) lives here, exactly as it did in the deleted
 * sheet host. Per-key bytes go through [onKey] / [onLongKey]; the host keeps
 * owning the open/close flag, so "stays open after a key press" is the host
 * simply not closing on [onKey].
 *
 * The wrapper [Box] is intentionally gesture-free: a plain layout node is
 * hit-test transparent, so touches that miss the card reach the terminal
 * underneath it.
 *
 * @param mainSections key grid for the main page.
 * @param ctrlSections key grid for the Ctrl picker page.
 * @param onKey invoked for every key tap; does NOT close the palette.
 * @param onLongKey invoked for a long-press action (e.g. the `^C ×2` cue).
 * @param onClose invoked by the close button.
 * @param initialPage test/render seam for the page to start on.
 * @param enabled when false, keys and page actions mute and stop firing.
 */
@Composable
fun TerminalHotkeysPaletteOverlay(
    mainSections: List<HotkeySection>,
    ctrlSections: List<HotkeySection>,
    onKey: (KeyBinding) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onLongKey: (KeyBinding) -> Unit = {},
    enabled: Boolean = true,
    ctrlFlowLabel: String = "Ctrl+…",
    longPressActions: Map<String, HotkeyLongPressAction> = emptyMap(),
    initialPage: TerminalHotkeysPage = TerminalHotkeysPage.Main,
) {
    var page by remember { mutableStateOf(initialPage) }
    var dragged by remember { mutableStateOf(false) }
    var pull by remember { mutableStateOf(Offset.Zero) }
    var paletteSize by remember { mutableStateOf(IntSize.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val insetPx = with(density) { PaletteInset.toPx() }

    fun applyDrag(delta: Offset) {
        dragged = true
        pull = clampPalettePull(pull + delta, paletteSize, containerSize)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        // The applied offset re-clamps the stored pull every placement, so a
        // shrunk viewport can never leave the card stranded off-screen.
        val applied = clampPalettePull(
            if (dragged) pull else Offset(-insetPx, -insetPx),
            paletteSize,
            containerSize,
        )
        // Never taller than the container minus its default inset, floored so
        // a tiny container still scrolls instead of collapsing to zero.
        val maxHeight = with(density) {
            (containerSize.height.toDp() - (PaletteInset * 2)).coerceAtLeast(160.dp)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(applied.x.roundToInt(), applied.y.roundToInt()) }
                .shadow(elevation = 8.dp, shape = PaletteShape, clip = false)
                .background(color = PocketShellColors.Surface, shape = PaletteShape)
                .border(border = BorderStroke(1.dp, PocketShellColors.Border), shape = PaletteShape)
                .width(PaletteWidth)
                .heightIn(max = maxHeight)
                .onSizeChanged { paletteSize = it }
                .testTag(TERMINAL_HOTKEYS_PALETTE_TAG),
        ) {
            PaletteHeader(
                page = page,
                enabled = enabled,
                onDrag = ::applyDrag,
                onBackToMain = { page = TerminalHotkeysPage.Main },
                onClose = onClose,
            )

            Crossfade(
                targetState = page,
                animationSpec = tween(durationMillis = 150),
                label = "terminal-hotkeys-palette-page",
            ) { currentPage ->
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    (if (currentPage == TerminalHotkeysPage.Main) mainSections else ctrlSections)
                        .forEach { section ->
                            HotkeySectionGrid(
                                section = section,
                                onKey = onKey,
                                onLongKey = onLongKey,
                                longPressActions = longPressActions,
                                enabled = enabled,
                            )
                        }

                    if (currentPage == TerminalHotkeysPage.Main) {
                        HotkeyPageAction(
                            label = ctrlFlowLabel,
                            enabled = enabled,
                            onClick = { page = TerminalHotkeysPage.Ctrl },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The palette's drag handle row: grabber, title, optional Ctrl-page back,
 * and the close button. Dragging is accepted anywhere on the handle EXCEPT
 * the close button, which is a sibling target — the handle shares no surface
 * with a key, so a drag can never send terminal input.
 */
@Composable
private fun PaletteHeader(
    page: TerminalHotkeysPage,
    enabled: Boolean,
    onDrag: (Offset) -> Unit,
    onBackToMain: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = PocketShellDensity.tapTargetMin)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount)
                        },
                    )
                }
                .testTag(TERMINAL_HOTKEYS_PALETTE_HANDLE_TAG)
                .semantics {
                    contentDescription = "Move the key palette"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            // The grabber pill — the visual "this moves" cue.
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 4.dp)
                    .background(
                        color = PocketShellColors.TextMuted,
                        shape = RoundedCornerShape(50),
                    ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (page == TerminalHotkeysPage.Ctrl) {
                Box(
                    modifier = Modifier
                        .size(PocketShellDensity.tapTargetMin)
                        .combinedClickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClickLabel = "Back to common keys",
                            onClick = onBackToMain,
                        )
                        .testTag(TERMINAL_HOTKEYS_PALETTE_BACK_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = PocketShellIcons.Back,
                        contentDescription = null,
                        tint = if (enabled) PocketShellColors.Accent else PocketShellColors.TextMuted,
                        modifier = Modifier.size(PocketShellDensity.metadataIcon),
                    )
                }
            }
            Text(
                text = if (page == TerminalHotkeysPage.Ctrl) "Ctrl + …" else "More keys",
                color = PocketShellColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .size(PocketShellDensity.tapTargetMin)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Close terminal keys",
                    onClick = onClose,
                )
                .testTag(TERMINAL_HOTKEYS_PALETTE_CLOSE_TAG)
                .semantics { contentDescription = "Close terminal keys" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PocketShellIcons.Close,
                contentDescription = null,
                tint = PocketShellColors.TextSecondary,
                modifier = Modifier.size(PocketShellDensity.metadataIcon),
            )
        }
    }
}

@Composable
private fun HotkeyPageAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PocketShellDensity.tapTargetMin)
            .background(PocketShellColors.AccentSoft, RoundedCornerShape(8.dp))
            .border(
                BorderStroke(1.dp, PocketShellColors.AccentDim),
                RoundedCornerShape(8.dp),
            )
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .testTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) PocketShellColors.Accent else PocketShellColors.TextMuted,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HotkeySectionGrid(
    section: HotkeySection,
    onKey: (KeyBinding) -> Unit,
    onLongKey: (KeyBinding) -> Unit,
    longPressActions: Map<String, HotkeyLongPressAction>,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = section.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
            color = PocketShellColors.TextMuted,
        )
        section.rows.forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowKeys.forEach { binding ->
                    HotkeySlot(
                        binding = binding,
                        enabled = enabled,
                        longPressAction = longPressActions[binding.label],
                        onTap = { onKey(binding) },
                        onLongPress = { onLongKey(binding) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(section.columns - rowKeys.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HotkeySlot(
    binding: KeyBinding,
    enabled: Boolean,
    longPressAction: HotkeyLongPressAction?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val textColor: Color = when {
        !enabled -> PocketShellColors.TextMuted
        binding.kind == KeyKind.Arrow -> PocketShellColors.TextSecondary
        else -> PocketShellColors.Text
    }
    var labelTruncated by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .heightIn(min = PocketShellDensity.tapTargetMin)
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
            .border(
                border = BorderStroke(1.dp, PocketShellColors.Border),
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTap()
                },
                onLongClickLabel = longPressAction?.accessibilityLabel,
                onLongClick = longPressAction?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                },
            )
            .semantics(mergeDescendants = true) {
                hotkeyLabelTruncated = labelTruncated
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = binding.label,
                color = textColor,
                fontFamily = if (binding.kind == KeyKind.Arrow) null else JetBrainsMonoFamily,
                fontSize = if (binding.kind == KeyKind.Arrow) 18.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { result -> labelTruncated = result.hasVisualOverflow },
            )
            if (longPressAction != null) {
                Text(
                    text = longPressAction.cue,
                    color = if (enabled) PocketShellColors.TextMuted else PocketShellColors.Border,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 8.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
