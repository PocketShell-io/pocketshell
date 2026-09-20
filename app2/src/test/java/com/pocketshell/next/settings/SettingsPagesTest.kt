package com.pocketshell.next.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.release.ReleaseCheckResult
import com.pocketshell.next.release.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1200dp")
class SettingsPagesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `terminal page renders a user facing sp control`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                TerminalSettingsScreen(
                settings = AppSettings(terminalTextSizePx = 32),
                onBack = {},
                onTerminalTextSizeChange = {},
                onShowCommonKeysChange = {},
                )
            }
        }

        composeRule.onNodeWithTag(SETTINGS_TERMINAL_PAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_TERMINAL_SIZE_SLIDER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("16 sp").assertIsDisplayed()
        composeRule.onNodeWithText("Text and input").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_TERMINAL_SAMPLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Show common keys").assertIsDisplayed()
        composeRule.onNodeWithText("Esc, Tab, Ctrl and arrows when typing.").assertIsDisplayed()
    }

    @Test
    fun `terminal size conversion includes density and font scale`() {
        val density = Density(2f, fontScale = 1.25f)

        assertEquals(16f, terminalTextSizeSpFromPx(40, density))
        assertEquals(40, terminalTextSizePxFromSp(16f, density))
    }

    /**
     * Issue #2814 N-3: the language group expands under its own row instead of
     * pushing `settings/voice/language`. Asserted here are the three things
     * the deleted page did — offer every option, report the pick, show what is
     * currently selected — plus the two only the inline shape can get wrong:
     * the group is absent until the row is tapped, and picking an option
     * closes it. That collapse IS the deleted "Done" button's replacement, so
     * a version that leaves the group hanging open fails here.
     */
    @Test
    fun `voice page opens the language group in place and a pick closes it`() {
        var changedTo: String? = null
        composeRule.setContent {
            VoiceSettingsScreen(
                settings = AppSettings(voiceLanguage = "de"),
                onBack = {},
                onVoiceLanguageChange = { changedTo = it },
            )
        }

        composeRule.onNodeWithText("German").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_VOICE_LANGUAGE_GROUP_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(SETTINGS_VOICE_LANGUAGE_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_VOICE_LANGUAGE_GROUP_TAG).assertIsDisplayed()
        AppSettings.VOICE_LANGUAGE_OPTIONS.forEach { option ->
            composeRule.onNodeWithTag(voiceLanguageOptionTag(option.code))
                .performScrollTo()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithTag(voiceLanguageOptionTag("de")).assertIsSelected()

        composeRule.onNodeWithTag(voiceLanguageOptionTag("ru")).performScrollTo().performClick()
        assertEquals("ru", changedTo)
        composeRule.onNodeWithTag(SETTINGS_VOICE_LANGUAGE_GROUP_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SETTINGS_VOICE_REVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_VOICE_RECOGNITION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("PocketShell asks for microphone access when you start dictating.")
            .assertIsDisplayed()
    }

    @Test
    fun `advanced reset is a real action and keeps the compatibility page reachable`() {
        var resetCount = 0
        composeRule.setContent {
            AdvancedSettingsScreen(
                settings = AppSettings(
                    agentSubmitEnterDelayMs = 300,
                    voiceSilenceThresholdSeconds = 8f,
                    usageWarnThresholdPercent = 90,
                ),
                onBack = {},
                onVoiceSilenceChange = {},
                onUsageWarnThresholdChange = {},
                onAgentSubmitEnterDelayChange = {},
                onResetAdvancedDefaults = { resetCount++ },
            )
        }

        composeRule.onNodeWithTag(SETTINGS_RESET_ADVANCED_TAG)
            .performScrollTo()
            .performClick()
        assertEquals(1, resetCount)
        composeRule.onNodeWithText("Reset advanced defaults").assertIsDisplayed()
    }

    /** The grace group's half of #2814 N-3 — same shape as the language one. */
    @Test
    fun `connections explain reconnect behavior through the inline grace control`() {
        var changedTo: Long? = null
        composeRule.setContent {
            ConnectionSettingsScreen(
                settings = AppSettings(),
                hosts = emptyList(),
                onBack = {},
                onBackgroundGraceChange = { changedTo = it },
                onOpenWorkspaceRoots = {},
            )
        }

        composeRule.onNodeWithText("Reconnect when I return").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This controls the phone’s connection. Remote sessions are not deliberately ended " +
                "when the app leaves the foreground.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_CONNECTION_GRACE_GROUP_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(SETTINGS_CONNECTION_GRACE_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_CONNECTION_GRACE_GROUP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(
            backgroundGraceOptionTag(AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS),
        ).assertIsSelected()

        composeRule.onNodeWithTag(backgroundGraceOptionTag(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS))
            .performScrollTo()
            .performClick()
        assertEquals(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS, changedTo)
        composeRule.onNodeWithTag(SETTINGS_CONNECTION_GRACE_GROUP_TAG).assertDoesNotExist()
    }

    @Test
    fun `about page uses the installed build value and update state summary`() {
        var openedLicenses = 0
        composeRule.setContent {
            AboutScreen(
                buildInfo = AppBuildInfo("0.5.0", 500),
                updateCheckState = SettingsUpdateCheckState.UpToDate,
                onBack = {},
                onOpenUpdate = {},
                onOpenLicenses = { openedLicenses++ },
            )
        }

        composeRule.onNodeWithTag(SETTINGS_ABOUT_PAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("v0.5.0 (500)").assertIsDisplayed()
        composeRule.onNodeWithText("This build is up to date").assertIsDisplayed()
        composeRule.onNodeWithText("Open-source licenses").performClick()
        assertEquals(1, openedLicenses)
    }

    @Test
    fun `update available exposes both real native handoff URLs`() {
        val info = ReleaseUpdateDisplay(
            tagName = "v0.5.1",
            htmlUrl = "https://github.com/PocketShell-io/pocketshell/releases/tag/v0.5.1",
            apkUrl = "https://example.com/pocketshell-0.5.1.apk",
            publishedDateLabel = "5 Sep 2026",
        )
        val opened = mutableListOf<String>()
        composeRule.setContent {
            UpdateScreen(
                state = SettingsUpdateCheckState.UpdateAvailable(info),
                onBack = {},
                onCheckForUpdates = {},
                onOpenUrl = { opened += it },
            )
        }

        composeRule.onNodeWithText("Release notes").performClick()
        composeRule.onNodeWithText("Open release").performClick()
        assertEquals(listOf(info.htmlUrl, info.apkUrl), opened)
    }

    /**
     * The #2636 D3 seam: the release-check adapter stays app-side and flattens
     * `ReleaseInfo` onto the pure `ReleaseUpdateDisplay` shape field by field,
     * so no `next.release` type reaches `shared:ui-screens`.
     */
    @Test
    fun `release check adapter flattens release info onto the pure display shape`() {
        val info = ReleaseInfo(
            tagName = "v0.5.1",
            htmlUrl = "https://github.com/PocketShell-io/pocketshell/releases/tag/v0.5.1",
            apkUrl = "https://example.com/pocketshell-0.5.1.apk",
            publishedDateLabel = "5 Sep 2026",
        )

        assertEquals(
            SettingsUpdateCheckState.UpdateAvailable(
                ReleaseUpdateDisplay(
                    tagName = "v0.5.1",
                    htmlUrl = info.htmlUrl,
                    apkUrl = info.apkUrl,
                    publishedDateLabel = "5 Sep 2026",
                ),
            ),
            settingsUpdateCheckState(checking = false, lastResult = ReleaseCheckResult.UpdateAvailable(info)),
        )
        assertEquals(SettingsUpdateCheckState.Checking, settingsUpdateCheckState(checking = true, lastResult = null))
        assertEquals(SettingsUpdateCheckState.UpToDate, settingsUpdateCheckState(checking = false, lastResult = ReleaseCheckResult.UpToDate))
        assertEquals(
            SettingsUpdateCheckState.Failed("rate-limited, try again later"),
            settingsUpdateCheckState(checking = false, lastResult = ReleaseCheckResult.Failed("rate-limited, try again later")),
        )
        assertEquals(SettingsUpdateCheckState.Idle, settingsUpdateCheckState(checking = false, lastResult = null))
    }

    @Test
    fun `failed update state remains distinct and offers retry`() {
        var retries = 0
        composeRule.setContent {
            UpdateScreen(
                state = SettingsUpdateCheckState.Failed("rate-limited, try again later"),
                onBack = {},
                onCheckForUpdates = { retries++ },
                onOpenUrl = {},
            )
        }

        composeRule.onNodeWithText("Couldn't check for updates: rate-limited, try again later")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Retry update check").performClick()
        assertEquals(1, retries)
    }
}
