package com.pocketshell.next.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.composer.COMPOSER_HISTORY_SHEET_TAG
import com.pocketshell.next.composer.COMPOSER_SHEET_TITLE
import com.pocketshell.next.composer.COMPOSER_SHEET_TITLE_PREFIX
import com.pocketshell.next.composer.COMPOSER_TOOLS_TAG
import com.pocketshell.next.composer.COMPOSER_TOOLS_TITLE
import com.pocketshell.next.composer.COMPOSER_TOOLS_TRIGGER_TAG
import com.pocketshell.next.composer.ComposerBar
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.MESSAGE_HISTORY_TITLE
import com.pocketshell.next.composer.MessageHistorySheet
import com.pocketshell.next.composer.PromptComposerContent
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PALETTE_TITLE
import com.pocketshell.uikit.components.TerminalHotkeysPaletteOverlay
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #2802 C-5: one header grammar for everything `SessionScreen` opens.
 *
 * The #2635 audit's finding was that a single screen opened four surfaces with
 * three different naming schemes — a noun the sheet acts on
 * (`SessionSwitcherSheet` "Sessions", `TerminalActionsSheet` "Terminal"), an
 * instruction (`ComposerToolsPanel` "Add to input"), and a title that named the
 * affordance rather than the thing (`TerminalHotkeysPaletteOverlay` "More
 * keys", while its own close button already said "Close terminal keys"). This
 * pins all four on the noun rule.
 *
 * The audit named four surfaces; `SessionScreen` actually opens SIX titled
 * ones — [PromptComposerContent] (`SessionScreen:696`) and
 * [MessageHistorySheet] (`SessionScreen:789`) already obeyed the noun rule, so
 * they are pinned here rather than left to drift off it later. A map that
 * claims completeness has to actually be complete, or the next sheet added to
 * this screen picks its own grammar unchallenged.
 *
 * [noSessionSheetTitleIsAnInstruction] is the class-covering half (D31): it
 * fails for any FUTURE title written in the instruction form, not only for the
 * one instance the audit found.
 */
@RunWith(AndroidJUnit4::class)
class SessionSheetTitlesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theTerminalActionsSheetIsTitledAfterWhatItActsOn() {
        composeRule.setContent {
            PocketShellTheme {
                TerminalActionsSheetContent(
                    onSessions = {},
                    onBrowseFiles = {},
                    onCopySelection = {},
                    onDetach = {},
                    onEndSession = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(TERMINAL_ACTIONS_TITLE).assertIsDisplayed()
    }

    @Test
    fun theSessionSwitcherIsTitledAfterWhatItActsOn() {
        composeRule.setContent {
            PocketShellTheme {
                SessionSwitcherSheet(
                    currentSessionName = "work-1",
                    state = SessionSwitcherUiState(sessions = emptyList<SessionRow>()),
                    onNewSession = {},
                    onOpenSession = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(SESSION_SWITCHER_TITLE).assertIsDisplayed()
    }

    /**
     * #2802 C-3/C-5 reproduce-first: "Add to input" was an instruction, and one
     * that had stopped being true — the panel ended with a "Clear draft" row
     * that adds nothing to the input. This fails on the pre-#2802 tree.
     */
    @Test
    fun theComposerToolsPanelIsTitledAfterWhatItActsOn() {
        composeRule.setContent {
            PocketShellTheme {
                ComposerBar(
                    state = ComposerUiState(),
                    onDraftChange = {},
                    onSend = {},
                    onInsert = {},
                    onAttach = {},
                    onMicTap = {},
                    onCancelRecording = {},
                    onToggleHistory = {},
                    onTogglePreview = {},
                    onRemoveAttachment = {},
                    onDismissNotice = {},
                    onDiscard = {},
                )
            }
        }

        composeRule.onNodeWithTag(COMPOSER_TOOLS_TRIGGER_TAG).performClick()
        composeRule.onNodeWithTag(COMPOSER_TOOLS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(COMPOSER_TOOLS_TITLE).assertIsDisplayed()
    }

    /**
     * #2802 C-5 reproduce-first: the palette titled itself "More keys" — the
     * name of the bar button that opens it, not of the thing itself. This fails
     * on the pre-#2802 tree.
     */
    @Test
    fun theHotkeysPaletteIsTitledAfterWhatItActsOn() {
        composeRule.setContent {
            PocketShellTheme {
                TerminalHotkeysPaletteOverlay(
                    mainSections = HOTKEY_PALETTE_MAIN_SECTIONS,
                    ctrlSections = HOTKEY_CTRL_SECTIONS,
                    onKey = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText(TERMINAL_HOTKEYS_PALETTE_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("More keys").assertDoesNotExist()
    }

    /**
     * #2802 C-5: the composer sheet's own header. Untargeted it is the noun
     * "Prompt Composer"; bound to a session it becomes
     * "[COMPOSER_SHEET_TITLE_PREFIX]<session>" — still the input it acts on,
     * now saying which one.
     */
    @Test
    fun theComposerSheetIsTitledAfterWhatItActsOn() {
        composeRule.setContent {
            PocketShellTheme {
                PromptComposerContent(
                    state = ComposerUiState(),
                    targetLabel = "work-1",
                    onDraftChange = {},
                    onSend = {},
                    onInsert = {},
                    onAttach = {},
                    onMicTap = {},
                    onCancelRecording = {},
                    onToggleHistory = {},
                    onTogglePreview = {},
                    onRemoveAttachment = {},
                    onDismissNotice = {},
                    onClose = {},
                    onDiscard = {},
                )
            }
        }

        composeRule.onNodeWithText(COMPOSER_SHEET_TITLE_PREFIX + "work-1").assertIsDisplayed()
    }

    /** #2802 C-5: the history sheet is "Recent prompts" — the thing it lists. */
    @Test
    fun theHistorySheetIsTitledAfterWhatItActsOn() {
        composeRule.setContent {
            PocketShellTheme {
                MessageHistorySheet(messages = emptyList(), onPick = {}, onDismiss = {})
            }
        }

        composeRule.onNodeWithTag(COMPOSER_HISTORY_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(MESSAGE_HISTORY_TITLE).assertIsDisplayed()
    }

    /**
     * The rule, not the four instances: a title is the noun the surface acts
     * on, so it cannot open with an imperative verb. `SessionScreen` is one
     * screen — a user who learns "the header names the thing" on one of its
     * sheets must not be re-taught on the next.
     */
    @Test
    fun noSessionSheetTitleIsAnInstruction() {
        val titles = mapOf(
            "TerminalActionsSheet" to TERMINAL_ACTIONS_TITLE,
            "SessionSwitcherSheet" to SESSION_SWITCHER_TITLE,
            "ComposerToolsPanel" to COMPOSER_TOOLS_TITLE,
            "TerminalHotkeysPaletteOverlay" to TERMINAL_HOTKEYS_PALETTE_TITLE,
            "PromptComposerSheet" to COMPOSER_SHEET_TITLE,
            "PromptComposerSheet (targeted)" to COMPOSER_SHEET_TITLE_PREFIX.trim(),
            "MessageHistorySheet" to MESSAGE_HISTORY_TITLE,
        )

        assertEquals(
            "every titled surface SessionScreen opens is covered here — add the new one rather " +
                "than letting it pick its own grammar (#2802 C-5). SessionScreen also opens a " +
                "ConfirmDialog, which is deliberately absent: a confirm prompt asks a question " +
                "(\"Stop session?\") and design-system.md gives dialogs their own pattern.",
            7,
            titles.size,
        )

        titles.forEach { (surface, title) ->
            val firstWord = title.substringBefore(' ').trim()
            assertTrue(
                "$surface is titled \"$title\": a sheet header names the noun it acts on, " +
                    "not what to do with it (#2802 C-5)",
                firstWord.lowercase() !in INSTRUCTION_VERBS,
            )
            assertTrue("$surface has an empty title", title.isNotBlank())
        }
    }

    private companion object {
        /** The imperative openings a header must not use. */
        val INSTRUCTION_VERBS = setOf(
            "add", "browse", "choose", "clear", "close", "create", "delete", "edit", "enter",
            "find", "insert", "manage", "open", "pick", "search", "select", "send",
            "set", "show", "start", "stop", "switch", "tap", "view",
        )
    }
}
