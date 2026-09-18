package com.pocketshell.next.hosts

/** Which form field a failed submit should point at. */
enum class HostFormField { Name, Hostname, Port, Username, Key }

/**
 * Per-field validation messages. `null` means the field is clean; the whole
 * struct is clean until the first submit attempt, so an untouched form does not
 * open covered in red.
 */
data class HostFormErrors(
    val name: String? = null,
    val hostname: String? = null,
    val port: String? = null,
    val username: String? = null,
    val key: String? = null,
) {
    val isClean: Boolean
        get() = name == null && hostname == null && port == null && username == null && key == null

    /** The field a rejected submit should move focus to, in reading order. */
    val firstInvalid: HostFormField?
        get() = when {
            name != null -> HostFormField.Name
            hostname != null -> HostFormField.Hostname
            port != null -> HostFormField.Port
            username != null -> HostFormField.Username
            key != null -> HostFormField.Key
            else -> null
        }
}

/**
 * The add/edit form's state.
 *
 * [port] is a `String`, not an `Int`: a half-typed port ("2", "22") is a legal
 * intermediate state of a text field, and modelling it as an `Int` forces
 * either a crash or a silent value on every keystroke. Parsing happens once, in
 * app2's `AddEditHostViewModel.validate` — the shared module carries only the
 * presentation state, never the persistence or validation side effects.
 */
data class HostFormState(
    val name: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    /** Optional host-side usage command override; blank means use the default. */
    val usageCommand: String = "",
    val selectedKeyId: Long? = null,
    val errors: HostFormErrors = HostFormErrors(),
    /** True while an existing host is being read; false for Add, which has nothing to read. */
    val loading: Boolean = false,
    /** True when this form is editing a stored host rather than creating one. */
    val editing: Boolean = false,
    /** One-shot: the row was written and the screen should navigate away. */
    val saved: Boolean = false,
    /** True while the current details are being persisted for a connection test. */
    val testingConnection: Boolean = false,
    /** One-shot host id for the real connection gate to dial. */
    val testConnectionHostId: Long? = null,
)
