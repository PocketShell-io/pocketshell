package com.pocketshell.next.terminal

/**
 * What the user reads when a session goes away, and how failures are put into
 * words — extracted from [SessionViewModel] (issue #2495) because they are pure
 * functions of their arguments, with no ViewModel state behind them.
 */
internal object SessionEndMessages {

    /**
     * The message for a session that went away of its own accord.
     *
     * An exit status is included when the host reported one, because it is the
     * difference between "you typed `exit`" (0) and "the attach command could
     * not find that session" (3) — the same distinction `pocketshell sessions
     * attach` documents in its exit codes.
     */
    fun ended(name: String?, exitCode: Int?): String {
        val subject = if (name == null) "The session" else "Session \"$name\""
        return if (exitCode == null) {
            "$subject ended."
        } else {
            "$subject ended (exit $exitCode)."
        }
    }

    /**
     * The message for when THIS screen's connection was closed because someone
     * asked for it to end, rather than lost or handed back by the grace window
     * (issues #2477/#2487).
     */
    fun closed(name: String?): String {
        val subject = if (name == null) "The session" else "Session \"$name\""
        return "$subject ended: the connection was closed."
    }

    /** Best user-facing one-liner for a thrown failure. */
    fun describe(failure: Throwable): String =
        failure.message ?: failure::class.simpleName ?: "unknown error"
}
