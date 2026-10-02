package com.pocketshell.app;

/**
 * A sign-in or authenticated-request failure with a stable code the Account
 * screen branches on. Messages are written for the user and never contain a
 * token.
 */
public final class SyncAuthException extends Exception {
    /** The user dismissed the Google account chooser. */
    public static final String CANCELLED = "SIGN_IN_CANCELLED";
    /** Google sign-in is not set up for this package/signing certificate, or no account is available. */
    public static final String UNAVAILABLE = "SIGN_IN_UNAVAILABLE";
    /** Sign-in failed for another reason. */
    public static final String FAILED = "SIGN_IN_FAILED";
    /** No usable sign-in: signed out, or the token expired and could not be renewed silently. */
    public static final String NOT_SIGNED_IN = "NOT_SIGNED_IN";
    /** The sign-in record could not be stored or read from encrypted storage. */
    public static final String STORAGE = "SIGN_IN_STORAGE_FAILED";
    /** The sync service could not be reached. */
    public static final String NETWORK = "SYNC_NETWORK_FAILED";
    /** The request was not one the sync API accepts. */
    public static final String INVALID_REQUEST = "SYNC_INVALID_REQUEST";

    public final String code;

    public SyncAuthException(String code, String message) {
        super(message);
        this.code = code;
    }
}
