package com.pocketshell.app;

import com.hierynomus.sshj.common.KeyDecryptionFailedException;

import java.io.IOException;

import net.schmizz.sshj.userauth.UserAuthException;

/**
 * Maps a failed stored-key (key-handle) SSH connect to the native error code
 * the shared core already understands, with a fixed, secret-free message.
 *
 * <p>The code is load-bearing: pocketshell-core's ConnectionController retries
 * every dial failure except AUTH_FAILED, HOST_KEY_REJECTED and
 * INVALID_ARGUMENT. A wrong passphrase, a rejected key or an unreadable stored
 * key must therefore never collapse into a retryable code, or the reconnect
 * loop keeps dialling a connection that cannot succeed. The message is chosen
 * here and never copied from the exception, because SSHJ/key-parser messages
 * can echo key material or file paths.
 */
final class KeyHandleConnectFailures {
    static final String LOG_TAG = "PocketShellSshKey";
    static final String AUTH_FAILED = "AUTH_FAILED";
    static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    static final String CONNECTION_LOST = "CONNECTION_LOST";
    static final String SSH_IO = "SSH_IO";

    static final String KEY_LOCKED_MESSAGE =
        "The SSH key could not be unlocked. Check the key passphrase.";
    static final String KEY_REJECTED_MESSAGE = "The server did not accept the selected SSH key.";
    static final String KEY_UNREADABLE_MESSAGE =
        "The stored SSH key could not be read. Re-import it or choose another key.";
    static final String CONNECTION_MESSAGE = "The SSH connection failed before the key was accepted.";
    static final String GENERIC_MESSAGE = "SSH key connection failed.";

    private static final int MAX_CAUSE_DEPTH = 16;

    static final class Classified {
        final String code;
        final String message;

        Classified(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private KeyHandleConnectFailures() {}

    /** Failure while resolving the opaque handle in the native vault. */
    static Classified vaultUnavailable() {
        return new Classified(INVALID_ARGUMENT, KEY_UNREADABLE_MESSAGE);
    }

    static Classified classify(Throwable error) {
        boolean userAuth = false;
        boolean keyUnreadable = false;
        boolean io = false;
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth += 1) {
            if (isDecryptionFailure(current)) return new Classified(AUTH_FAILED, KEY_LOCKED_MESSAGE);
            if (current instanceof UserAuthException) {
                userAuth = true;
                // SSHJ wraps a key provider that cannot produce the key as
                // "Problem getting public key from ..." before any server verdict.
                String message = current.getMessage();
                if (message != null && message.startsWith("Problem getting public key")) keyUnreadable = true;
            } else if (current instanceof IllegalArgumentException) {
                keyUnreadable = true;
            } else if (current instanceof IOException) {
                io = true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        if (keyUnreadable) return new Classified(INVALID_ARGUMENT, KEY_UNREADABLE_MESSAGE);
        if (userAuth) return new Classified(AUTH_FAILED, KEY_REJECTED_MESSAGE);
        if (io) return new Classified(CONNECTION_LOST, CONNECTION_MESSAGE);
        return new Classified(SSH_IO, GENERIC_MESSAGE);
    }

    private static boolean isDecryptionFailure(Throwable error) {
        if (error instanceof KeyDecryptionFailedException) return true;
        String name = error.getClass().getSimpleName();
        // PKCS#8/PEM decryptors outside SSHJ's own key readers.
        return name.equals("EncryptionException") || name.equals("PasswordException");
    }
}
