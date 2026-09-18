package com.pocketshell.next.composer

/**
 * Slash-command sheet presentation tags, extracted from app2's
 * `ComposerBar.kt` with the slash family they name (#2636 D5).
 *
 * Lives in the shared presentation module; the tools row that opens the sheet
 * and the sheet's send path stay in app2 and reference these by the same
 * FQCN — the move changes no reference site.
 */

/** The slash-command autocomplete sheet itself. */
const val COMPOSER_SLASH_TAG: String = "composer-slash-sheet"

/** The composer-tools row that opens the slash sheet. */
const val COMPOSER_SLASH_TRIGGER_TAG: String = "composer-slash-trigger"

/** Stable tag for one command row inside the slash sheet. */
fun composerSlashRowTag(command: String): String = "composer-slash-row:$command"
