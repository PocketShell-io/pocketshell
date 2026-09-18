package com.pocketshell.core.hostapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Why a session the host warns about died (issue #2771). */
enum class WarningKind {
    /** The kernel OOM killer terminated the session's main process. */
    OOM,

    /** The session died without a recorded exit or a fatal finalization. */
    CRASH;

    companion object {
        /**
         * Maps the host's `kind` string. An unrecognised value keeps the row
         * with a `null` kind — forward compatibility never costs a row — the
         * same rule `agent_state` follows in [SessionsJson].
         */
        fun fromWire(raw: String?): WarningKind? = when (raw) {
            "oom" -> OOM
            "crash" -> CRASH
            else -> null
        }
    }
}

/**
 * One unacknowledged crash/OOM warning from `pocketshell sessions warnings
 * --json` (issue #2771).
 *
 * Everything is nullable: a row the host cannot fully describe still carries
 * its [detail], and the caller degrades field by field instead of dropping the
 * warning. Warnings persist on the host (surviving session pruning) until
 * acknowledged via `sessions ack`.
 */
data class WarningRow(
    /** The aplexer session UUID the warning belongs to. */
    val session: String?,
    /** Absolute workspace path on the host. */
    val workspace: String?,
    /** The aplexer tag inside [workspace]. */
    val tag: String?,
    /** The engine the session was started as, when the host reported one. */
    val engine: String?,
    /** Why it died; `null` for a kind this client does not know yet. */
    val kind: WarningKind?,
    /** The host's own one-sentence human explanation. */
    val detail: String?,
    /** When the host recorded the death (epoch millis). */
    @SerialName("created_at_ms") val createdAtMs: Long?,
) {

    /**
     * The `sessions ack` selector that addresses exactly this warning. The
     * fully-qualified `workspace:tag` pair comes first — it is the identity
     * every surface (banner label included) shows, and aplexer matches it
     * against the warning store itself, so it keeps working after the
     * session's own record was pruned. The session UUID is the fallback for a
     * row the host described only partially. `null` when the row carries
     * neither — a warning with no address is acknowledged via clear-all
     * only, never guessed at.
     */
    val ackSelector: String?
        get() = tag?.takeIf { it.isNotBlank() }?.let { label ->
            workspace?.takeIf { it.isNotBlank() }?.let { path -> "$path:$label" }
        } ?: session?.takeIf { it.isNotBlank() }
}

/**
 * Parser for `pocketshell sessions warnings --json`: a plain JSON array
 * (possibly empty) of warning rows, with no schema envelope — the array is the
 * whole document, so there is no [HostCliError.TooOld] gate here. An old
 * helper that lacks the verb fails the exec itself, and the UI swallows that
 * failure; the tree must keep working (issue #2771).
 *
 * A row that is not a JSON object fails the whole parse, matching
 * [SessionsJson]: a silently short warning list would hide a death the user
 * should see.
 */
object WarningsJson {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * Parses [raw] stdout into warning rows. Never throws for bad input: every
     * failure comes back as `Result.failure(HostCliError)`.
     */
    fun parseWarnings(raw: String): Result<List<WarningRow>> {
        val root = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            return Result.failure(
                HostCliError.Malformed("response was not valid JSON", e),
            )
        }

        val array = root as? JsonArray
            ?: return Result.failure(
                HostCliError.Malformed("expected a JSON array at the top level"),
            )

        val rows = array.map { element ->
            val obj = element as? JsonObject
                ?: return Result.failure(
                    HostCliError.Malformed("expected every warning to be a JSON object"),
                )
            try {
                json.decodeFromJsonElement<WarningRowWire>(obj).toModel()
            } catch (e: Exception) {
                return Result.failure(
                    HostCliError.Malformed(
                        "a warning row did not match the expected shape " +
                            "(${e.message ?: e::class.simpleName})",
                        e,
                    ),
                )
            }
        }
        return Result.success(rows)
    }

    @Serializable
    private data class WarningRowWire(
        val session: String? = null,
        val workspace: String? = null,
        val tag: String? = null,
        val engine: String? = null,
        val kind: String? = null,
        val detail: String? = null,
        @SerialName("created_at_ms") val createdAtMs: Long? = null,
    ) {
        fun toModel(): WarningRow = WarningRow(
            session = session,
            workspace = workspace,
            tag = tag,
            engine = engine,
            kind = WarningKind.fromWire(kind),
            detail = detail,
            createdAtMs = createdAtMs,
        )
    }
}
