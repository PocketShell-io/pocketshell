package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.Test;

public final class SshCapabilityPluginCloseTest {
    @Test
    public void rawSocketFallbackClosesSocketWhenSshjDisconnectThrows() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            Socket clientSocket = new Socket("127.0.0.1", listener.getLocalPort());
            try (Socket serverSocket = listener.accept()) {
                SshCapabilityPlugin.ClientCloseAttempt attempt = SshCapabilityPlugin.closeSshjTransportAndSocket(
                    clientSocket,
                    () -> { throw new IOException("injected disconnect failure"); },
                    () -> { throw new IOException("injected close failure"); }
                );

                assertTrue("raw fallback must close the captured SSH socket", clientSocket.isClosed());
                assertTrue("fallback use must be reported", attempt.rawSocketCloseFallbackUsed);
                assertTrue("physical close must be reported", attempt.socketClosed);
                assertEquals("IOException", attempt.disconnectErrorClass);
                assertEquals("IOException", attempt.clientCloseErrorClass);
                assertEquals("", attempt.rawSocketCloseErrorClass);
                assertTrue("successful raw close must not report a close error", attempt.rawSocketCloseErrorClass.isEmpty());
            } finally {
                if (!clientSocket.isClosed()) clientSocket.close();
            }
        }
    }
}
