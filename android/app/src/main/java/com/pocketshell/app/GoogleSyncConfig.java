package com.pocketshell.app;

import java.util.regex.Pattern;

/**
 * Fixed values for Google sign-in and settings sync (issue #3020).
 *
 * <p>The sync backend is the shared {@code pocketshell-sync} stack
 * (aws-infra {@code sandbox/pocketshell-sync}): an API Gateway JWT authorizer
 * that accepts Google ID tokens whose audience is either the desktop client or
 * the PocketShell "Web application" client. Credential Manager mints an ID
 * token whose {@code aud} is the {@code serverClientId} it was asked for, so
 * the phone asks for the web client's ID: the deployed authorizer already
 * accepts that audience and no backend change is needed.
 *
 * <p>The web client ID alone does not let an Android app sign in. Google also
 * requires an OAuth client of type <b>Android</b> in the same Cloud project,
 * registered with the APK's package name and signing-certificate SHA-1. That
 * registration is a console action, documented in docs/settings-sync.md; until
 * it exists for a package, Credential Manager refuses the request and the
 * Account screen says which package needs registering.
 */
final class GoogleSyncConfig {
    /**
     * The PocketShell "Web application" OAuth client in Google Cloud project
     * 1035162854462 (pocketshell-web's {@code googleClientId}; the deployed
     * sync stack's {@code GoogleWebClientId}). A public identifier, not a
     * secret.
     */
    static final String SERVER_CLIENT_ID =
            "1035162854462-kkqius5o2ni136ed6l58iig5pdpeh4u6.apps.googleusercontent.com";

    /** {@code ApiUrl} of the pocketshell-sync stack; the desktop and web clients use the same one. */
    static final String SYNC_API_URL = "https://a7sota2qic.execute-api.eu-west-1.amazonaws.com";

    /** Encrypted preferences file holding the sign-in record; nothing else lives in it. */
    static final String AUTH_PREFERENCES_FILE = "pocketshell-sync-auth";

    /** Slot names the sync API accepts. */
    static final Pattern SLOT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** The HTTP API caps a request at 10 KB; the encrypted envelope must fit inside it. */
    static final int MAX_REQUEST_BODY_BYTES = 10 * 1024;

    /** An ID token is treated as expired this long before its {@code exp}. */
    static final long EXPIRY_SKEW_SECONDS = 60;

    private GoogleSyncConfig() {
    }
}
