package com.pocketshell.app.smoke;

/** PTY fixture: a canonical Enter gates READY before the measured raw input. */
public final class FastKeyReceiverCommand {
    private FastKeyReceiverCommand() {}

    public static String build(String rawPath, int byteCount, String readyMarker, String doneMarker) {
        // Canonical mode buffers the one Enter even if the host has not reached
        // read yet. Starting in raw mode would race with that setup byte.
        return "(set +m; stty sane -echo; IFS= read -r receiverStart || exit; stty raw -echo; "
                + "(while :; do stty size </dev/tty >> " + rawPath + ".geometry"
                + "; sleep 0.1; done) & ptyGeometryMonitor=$!; " + printMarker(readyMarker, "\\r\\n")
                + "dd bs=1 count=" + byteCount + " status=none > " + rawPath
                + "; stty sane; kill \"$ptyGeometryMonitor\" 2>/dev/null || true;"
                + " wait \"$ptyGeometryMonitor\" 2>/dev/null || true; " + printMarker(doneMarker, "\\n") + ")";
    }

    private static String printMarker(String marker, String lineBreak) {
        int split = marker.length() / 2;
        // The command echo must never satisfy the exact visible-output oracle.
        return "printf '" + lineBreak + "%s%s" + lineBreak + "' "
                + shellQuote(marker.substring(0, split)) + " " + shellQuote(marker.substring(split)) + "; ";
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
