package com.pocketshell.next.files

import java.util.Locale

/**
 * Human file size, matching the explorer rows and the transfer banner.
 *
 * Pinned to [Locale.US] rather than the default: the decimal separator is not a
 * localisation the app does anywhere else (paths, ports and byte counts are all
 * rendered machine-style), and a default-locale format would make the same
 * string read `1,5 KB` on one device and `1.5 KB` on another.
 *
 * Lives in the shared presentation module (#2636 D9), moved verbatim out of
 * app2's `FileExplorerViewModel.kt` because the extracted [TransfersScreen]
 * needs it: pure formatting, no transport or storage type in sight. `internal`
 * → public is the only delta — app2's explorer, viewer and binary renderer keep
 * calling it unchanged, the package name being the same on both sides.
 */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
}
