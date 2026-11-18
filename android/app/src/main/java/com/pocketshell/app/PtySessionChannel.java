package com.pocketshell.app;

import java.nio.charset.Charset;
import net.schmizz.sshj.connection.Connection;
import net.schmizz.sshj.connection.channel.direct.SessionChannel;
import net.schmizz.sshj.connection.channel.direct.Signal;
import net.schmizz.sshj.transport.TransportException;

/**
 * A PTY session channel whose post-open channel requests (window-change,
 * signal) can never reach the host after this side's CLOSE (#3039).
 *
 * When the far end's process exits, sshd sends CLOSE and sshj's reader
 * thread answers with ours at once; from then on the host has freed the
 * channel number, and OpenSSH answers ANY channel request for a freed
 * number by dropping the whole connection
 * ({@code server_input_channel_req: unknown channel N}) — every other
 * channel and the session list with it. sshj itself writes channel
 * requests without looking at the channel's state, so a resize that races
 * a session's exit (a pane pushing its geometry as a session ends) turned
 * a clean session end into a transport loss and a full reconnect.
 *
 * A request checks a {@code closeSent} flag and writes under one lock;
 * our CLOSE first sets that flag under the same lock and only then, with
 * the lock RELEASED, goes out. So every request either finished its write
 * before the flag was set (and therefore before our CLOSE was written) or
 * sees the flag and is dropped: the channel is dead, so there is nothing
 * for it to do. A request sent after the host's CLOSE but before ours is
 * still delivered — the host keeps the channel until it has both, and
 * ignores it (verified against OpenSSH 9.7, core's
 * SessionEndVerdict integration suite).
 *
 * The guard is never held across sshj's own close machinery. sshj's
 * {@code close()} holds its channel {@code openCloseLock} when it calls
 * {@code sendClose()}, while the reader thread answering the host's CLOSE
 * reaches {@code sendClose()} first and takes {@code openCloseLock}
 * inside it; holding the guard around {@code super.sendClose()} took the
 * two locks in opposite orders on those two paths and deadlocked the
 * connection's reader and the plugin thread (#3039 review B1). A request
 * holds the guard only around its own transport write, which never takes
 * {@code openCloseLock}.
 */
class PtySessionChannel extends SessionChannel {
    private final Object requestGuard = new Object();
    private boolean closeSent;

    PtySessionChannel(Connection connection, Charset remoteCharset) {
        super(connection, remoteCharset);
    }

    @Override
    protected void sendClose() throws TransportException {
        // Waits out a request write already in flight, then lets go
        // before sshj's own sendClose takes openCloseLock.
        synchronized (requestGuard) {
            closeSent = true;
        }
        super.sendClose();
    }

    @Override
    public void changeWindowDimensions(int cols, int rows, int width, int height) throws TransportException {
        synchronized (requestGuard) {
            if (closeSent) return;
            super.changeWindowDimensions(cols, rows, width, height);
        }
    }

    @Override
    public void signal(Signal signal) throws TransportException {
        synchronized (requestGuard) {
            if (closeSent) return;
            super.signal(signal);
        }
    }

    /** True once this side's CLOSE went out: no request will follow it. */
    boolean closeSent() {
        synchronized (requestGuard) {
            return closeSent;
        }
    }
}
