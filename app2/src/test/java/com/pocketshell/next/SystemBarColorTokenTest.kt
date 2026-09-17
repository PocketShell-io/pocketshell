package com.pocketshell.next

import androidx.compose.ui.graphics.toArgb
import com.pocketshell.uikit.theme.PocketShellColors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2756 (#2635 R8): MainActivity's status/navigation-bar colour used to
 * be a hard-coded `rgb(16, 23, 30)` — byte-identical to the shipped Background
 * token but linked to nothing, so a palette change in ui-kit's `Color.kt`
 * silently left the system bars behind. Two pins keep the linkage real:
 *
 * - the token value is pinned to the exact ARGB the activity used to
 *   hard-code, so the palette contract cannot drift silently in either
 *   direction (a Background change reddens this and forces a conscious call);
 * - MainActivity.kt is source-scanned so the assignments stay on the token and
 *   the literal cannot creep back in.
 *
 * app2 has no Hilt activity test lane, so there is no Robolectric `onCreate`
 * path to read `window.statusBarColor` from — this is the same plain-JVM
 * linkage shape as [RenderHarnessPolicyTest]'s source pins.
 */
class SystemBarColorTokenTest {

    /** The colour the activity used to hard-code, as an ARGB int: rgb(16, 23, 30). */
    private val formerSystemBarArgb: Int =
        (0xFF shl 24) or (16 shl 16) or (23 shl 8) or 30

    @Test
    fun backgroundTokenPinsTheFormerSystemBarValue() {
        assertEquals(formerSystemBarArgb, PocketShellColors.Background.toArgb())
    }

    @Test
    fun mainActivitySystemBarsComeFromTheBackgroundToken() {
        val source = locate(
            "app2/src/main/java/com/pocketshell/next/MainActivity.kt",
            "src/main/java/com/pocketshell/next/MainActivity.kt",
        ).readText()

        assertTrue(
            "MainActivity.kt re-hardcodes the old system-bar literal — keep it on the " +
                "ui-kit Background token (#2756)",
            !Regex("""rgb\(16,\s*23,\s*30\)""").containsMatchIn(source),
        )
        assertTrue(
            "MainActivity.kt re-hardcodes the old system-bar hex — keep it on the " +
                "ui-kit Background token (#2756)",
            !Regex("""(?i)0xff10171e""").containsMatchIn(source),
        )
        for (bar in listOf("statusBarColor", "navigationBarColor")) {
            val assignment = Regex("""window\.$bar\s*=\s*(\S.*)""")
                .find(source)
                ?.groupValues?.get(1)?.trim()
                ?: error("MainActivity.kt no longer sets window.$bar — update this pin")
            assertEquals(
                "window.$bar must come from the ui-kit Background token (#2756)",
                "PocketShellColors.Background.toArgb()",
                assignment,
            )
        }
    }

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.isFile }
            ?: error("Could not locate ${candidates.first()} from ${File(".").absolutePath}")
}
