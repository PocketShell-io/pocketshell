package com.pocketshell.next.tree

/**
 * The shared session-tree test-tag vocabulary: row/header tags keyed by the
 * host's own strings, the host-tools sheet tags, and the stop-session overflow
 * and confirmation copy. The live host workspaces/session screens, the
 * terminal session chrome and the androidTest journeys all import these, so
 * they live in their own file rather than on any one screen (issue #2726):
 * a screen can be reworked or deleted without moving the vocabulary every
 * consumer compiles against.
 *
 * Row and folder headers are keyed by the host's own strings so a journey
 * asserts against the session the fixture really reported, not against a
 * rendering-order index.
 */

/** The host-tools action that opens this host's file explorer (task P-3a). */
const val SESSION_TREE_FILES_TAG: String = "session-tree-files"

/** The host-tools action that opens this host's port-forward panel (task P-4). */
const val SESSION_TREE_PORTS_TAG: String = "session-tree-ports"

/** The host-tools action that opens this host's usage panel (issue #2532). */
const val SESSION_TREE_USAGE_TAG: String = "session-tree-usage"

fun sessionRowTag(name: String): String = "session-row-$name"

fun sessionRowMenuTag(name: String): String = "session-row-menu-$name"

fun folderHeaderTag(key: String): String = "folder-header-$key"

/** Overflow item and confirmation copy for ending a session (issue #2535). */
const val STOP_SESSION_ITEM_LABEL: String = "End session…"
const val STOP_SESSION_TITLE: String = "End Terminal?"
const val STOP_SESSION_CONFIRM_LABEL: String = "End session"
const val STOP_SESSION_ITEM_TAG: String = "session-stop-item"
const val STOP_SESSION_CONFIRM_TAG: String = "session-stop-confirm"
const val STOP_SESSION_CANCEL_TAG: String = "session-stop-cancel"
const val STOP_SESSION_TITLE_TAG: String = "session-stop-title"
const val STOP_SESSION_MESSAGE_TAG: String = "session-stop-message"

fun stopSessionMessage(name: String, workspace: String? = null, host: String? = null): String {
    val context = listOfNotNull(
        workspace?.trim()?.takeIf { it.isNotEmpty() }?.let { "workspace \"$it\"" },
        host?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" on ")
    val location = context.takeIf { it.isNotEmpty() }?.let { " in $it" }.orEmpty()
    return "End \"$name\"$location? This ends the session and anything running in it. There is no undo."
}
