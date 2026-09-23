package com.pocketshell.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.SessionChannel;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyFormat;
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil;
import net.schmizz.sshj.common.Factory;

/**
 * Narrow Android SSH I/O capability. This class owns sshj objects and bounded
 * channel/resource cleanup. Session commands, trust decisions, reconnect and
 * background grace policy remain in TypeScript.
 */
@CapacitorPlugin(name = "SshCapability")
public final class SshCapabilityPlugin extends Plugin {
    private static final int MAX_CHANNELS_PER_CONNECTION = 8;
    private static final int MAX_PTY_READ_BYTES = 32 * 1024;
    private static final int MAX_PTY_WRITE_BYTES = 32 * 1024;
    private static final int MAX_SFTP_TRANSFER_BYTES = 512 * 1024;
    private static final int MAX_EXEC_COMMAND_CHARS = 32 * 1024;
    private static final int MAX_EXEC_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_EXEC_TIMEOUT_MS = 120_000;
    private static final int MAX_SFTP_ENTRIES = 1000;
    private static final int MAX_ACTIVE_FORWARDS = 8;
    private static final Semaphore FORWARD_PERMITS = new Semaphore(MAX_ACTIVE_FORWARDS);
    private static final ExecutorService FORWARD_EXECUTOR = Executors.newFixedThreadPool(MAX_ACTIVE_FORWARDS, runnable -> {
        Thread thread = new Thread(runnable, "pocketshell-ssh-forward");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pocketshell-ssh-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, SshConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, SshPty> ptys = new ConcurrentHashMap<>();
    private final Map<String, SshForward> forwards = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void handleOnDestroy() {
        for (SshConnection connection : new ArrayList<>(connections.values())) {
            closeConnection(connection, "plugin-destroyed", false);
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            String generationId = requiredString(options, "generationId");
            String hostId = requiredString(options, "hostId");
            String hostname = requiredString(options, "hostname");
            int port = boundedInt(options, "port", 1, 65535, 22);
            String username = requiredString(options, "username");
            int connectTimeout = boundedInt(options, "connectTimeoutMs", 1000, 30_000, 20_000);
            JSObject credential = options.getJSObject("credential");
            if (credential == null) throw new PluginFailure("INVALID_ARGUMENT", "SSH credential is missing.");
            String credentialKind = requiredString(credential, "kind");
            JSObject expectedHostKey = options.getJSObject("expectedHostKey");
            String expectedKeyType = expectedHostKey == null ? null : requiredString(expectedHostKey, "keyType");
            String expectedKeyB64 = expectedHostKey == null ? null : requiredString(expectedHostKey, "keyB64");

            SSHClient client = new SSHClient();
            client.setConnectTimeout(connectTimeout);
            client.setTimeout(Math.min(connectTimeout, 10_000));
            PresentedHostKey presented = new PresentedHostKey();
            client.addHostKeyVerifier(new PinVerifier(expectedKeyType, expectedKeyB64, presented));
            SshConnection connection = null;
            try {
                client.connect(hostname, port);
                client.setTimeout(5000);
                if ("password".equals(credentialKind)) {
                    String password = requiredString(credential, "password");
                    char[] secret = password.toCharArray();
                    try {
                        client.authPassword(username, secret);
                    } finally {
                        PasswordUtils.blankOut(secret);
                    }
                } else if ("private-key".equals(credentialKind)) {
                    String pem = requiredString(credential, "privateKeyPem");
                    String passphrase = credential.getString("passphrase", "");
                    char[] secret = passphrase == null ? new char[0] : passphrase.toCharArray();
                    try {
                        KeyFormat format = KeyProviderUtil.detectKeyFileFormat(pem, secret.length > 0);
                        FileKeyProvider keyProvider = Factory.Named.Util.create(
                            client.getTransport().getConfig().getFileKeyProviderFactories(),
                            format.toString()
                        );
                        if (keyProvider == null) throw new PluginFailure("INVALID_ARGUMENT", "SSH private key format is not supported.");
                        keyProvider.init(pem, null, PasswordUtils.createOneOff(secret));
                        client.authPublickey(username, keyProvider);
                    } finally {
                        PasswordUtils.blankOut(secret);
                    }
                } else {
                    throw new PluginFailure("INVALID_ARGUMENT", "SSH credential kind is not supported.");
                }

                connection = new SshConnection(UUID.randomUUID().toString(), generationId, hostId, client);
                final SshConnection connected = connection;
                client.getTransport().setDisconnectListener((reason, message) -> {
                    if (connected.intentionalClose.get()) return;
                    connected.state = "lost";
                    CLEANUP_EXECUTOR.execute(() -> closeChildren(connected));
                    JSObject event = new JSObject();
                    event.put("connectionId", connected.connectionId);
                    event.put("generationId", connected.generationId);
                    event.put("state", "lost");
                    event.put("reason", message == null || message.isBlank() ? String.valueOf(reason) : message);
                    mainHandler.post(() -> notifyListeners("connectionState", event));
                });
                connections.put(connection.connectionId, connection);
                if (!connection.state.equals("connected") || !client.isConnected()) {
                    throw new PluginFailure("CONNECTION_LOST", "SSH connection ended during setup.");
                }
                JSObject hostKey = presented.asJson();
                return new JSObject()
                    .put("requestId", requestId)
                    .put("connectionId", connection.connectionId)
                    .put("generationId", generationId)
                    .put("hostKey", hostKey);
            } catch (Exception error) {
                if (connection != null) closeConnection(connection, "connect-failed", false);
                else closeClient(client);
                if (presented.keyType != null && !presented.trusted) {
                    JSObject details = presented.asJson();
                    throw new PluginFailure("HOST_KEY_REJECTED", "The SSH host key has not been trusted.", details, error);
                }
                throw failureFor(error);
            }
        });
    }

    @PluginMethod
    public void getConnectionState(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = findConnection(options);
            String state = connection == null ? "closed" : connection.state;
            if (connection != null && (connection.state.equals("connected") && !connection.client.isConnected())) {
                state = "lost";
                connection.state = state;
            }
            return new JSObject().put("requestId", requestId).put("state", state);
        });
    }

    @PluginMethod
    public void closeConnection(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = findConnection(options);
            if (connection != null) closeConnection(connection, "closed", false);
            return ack(requestId);
        });
    }

    @PluginMethod
    public void scheduleClose(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            long deadline = requiredLong(options, "deadlineEpochMs");
            synchronized (connection.graceLock) {
                if (connection.graceRunnable != null) mainHandler.removeCallbacks(connection.graceRunnable);
                Runnable[] holder = new Runnable[1];
                Runnable closeAtDeadline = () -> {
                    synchronized (connection.graceLock) {
                        if (connection.graceRunnable != holder[0]) return;
                        connection.graceRunnable = null;
                        connection.graceDeadlineEpochMs = null;
                    }
                    closeConnection(connection, "grace-expired", true);
                };
                holder[0] = closeAtDeadline;
                connection.graceRunnable = closeAtDeadline;
                connection.graceDeadlineEpochMs = deadline;
                if (!mainHandler.postDelayed(closeAtDeadline, Math.max(0L, deadline - System.currentTimeMillis()))) {
                    connection.graceRunnable = null;
                    connection.graceDeadlineEpochMs = null;
                    throw new PluginFailure("SCHEDULE_FAILED", "Could not schedule SSH grace closure.");
                }
            }
            return ack(requestId);
        });
    }

    @PluginMethod
    public void cancelScheduledClose(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = findConnection(options);
            boolean cancelled = false;
            if (connection != null) {
                synchronized (connection.graceLock) {
                    if (connection.graceRunnable != null) {
                        cancelled = true;
                        mainHandler.removeCallbacks(connection.graceRunnable);
                        connection.graceRunnable = null;
                        connection.graceDeadlineEpochMs = null;
                    }
                }
            }
            return ack(requestId).put("cancelled", cancelled);
        });
    }

    @PluginMethod
    public void exec(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String command = requiredString(options, "command");
            if (command.length() > MAX_EXEC_COMMAND_CHARS) throw new PluginFailure("INVALID_ARGUMENT", "SSH command is too long.");
            int timeoutMs = boundedInt(options, "timeoutMs", 1, MAX_EXEC_TIMEOUT_MS, 20_000);
            acquireChannel(connection);
            SessionChannel channel = null;
            Thread stdoutReader = null;
            Thread stderrReader = null;
            BoundedCapture stdout = new BoundedCapture(MAX_EXEC_OUTPUT_BYTES);
            BoundedCapture stderr = new BoundedCapture(MAX_EXEC_OUTPUT_BYTES);
            boolean timedOut = false;
            try {
                channel = (SessionChannel) connection.client.startSession();
                Session.Command commandChannel = channel.exec(command);
                SessionChannel activeChannel = channel;
                stdoutReader = streamThread(commandChannel.getInputStream(), stdout, "stdout");
                stderrReader = streamThread(commandChannel.getErrorStream(), stderr, "stderr");
                activeChannel.join(timeoutMs, TimeUnit.MILLISECONDS);
                if (activeChannel.isOpen()) {
                    timedOut = true;
                    closeQuietly(activeChannel);
                }
                joinReader(stdoutReader);
                joinReader(stderrReader);
                if (stdout.truncated || stderr.truncated) {
                    throw new PluginFailure("OUTPUT_LIMIT", "SSH command output exceeded the 1 MiB per-stream limit.");
                }
                Integer exitCode = commandChannel.getExitStatus();
                return new JSObject()
                    .put("requestId", requestId)
                    .put("connectionId", connection.connectionId)
                    .put("generationId", connection.generationId)
                    .put("exitCode", exitCode)
                    .put("stdout", stdout.asUtf8())
                    .put("stderr", stderr.asUtf8())
                    .put("timedOut", timedOut);
            } catch (Exception error) {
                if (error instanceof PluginFailure) throw (PluginFailure) error;
                throw failureFor(error);
            } finally {
                closeQuietly(channel);
                if (stdoutReader != null) stdoutReader.interrupt();
                if (stderrReader != null) stderrReader.interrupt();
                connection.channelPermits.release();
            }
        });
    }

    @PluginMethod
    public void openPty(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String command = requiredString(options, "command");
            if (command.length() > MAX_EXEC_COMMAND_CHARS) throw new PluginFailure("INVALID_ARGUMENT", "PTY command is too long.");
            int cols = boundedInt(options, "cols", 1, 1000, 80);
            int rows = boundedInt(options, "rows", 1, 1000, 24);
            String term = options.getString("term", "xterm-256color");
            if (term == null || term.isBlank() || term.length() > 128) throw new PluginFailure("INVALID_ARGUMENT", "PTY terminal type is invalid.");
            acquireChannel(connection);
            SessionChannel channel = null;
            try {
                channel = (SessionChannel) connection.client.startSession();
                channel.allocatePTY(term, cols, rows, 0, 0, Collections.emptyMap());
                Session.Command commandChannel = channel.exec(command);
                SshPty pty = new SshPty(UUID.randomUUID().toString(), connection, channel, commandChannel);
                ptys.put(pty.channelId, pty);
                return new JSObject()
                    .put("requestId", requestId)
                    .put("connectionId", connection.connectionId)
                    .put("generationId", connection.generationId)
                    .put("channelId", pty.channelId);
            } catch (Exception error) {
                closeQuietly(channel);
                connection.channelPermits.release();
                throw failureFor(error);
            }
        });
    }

    @PluginMethod
    public void readPty(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshPty pty = requirePty(options);
            int sequence = boundedInt(options, "sequence", 0, Integer.MAX_VALUE, 0);
            int maxBytes = boundedInt(options, "maxBytes", 1, MAX_PTY_READ_BYTES, MAX_PTY_READ_BYTES);
            int waitMs = boundedInt(options, "waitMs", 0, 500, 0);
            byte[] resultBytes;
            int resultSequence;
            boolean eof;
            synchronized (pty.readLock) {
                if (sequence != pty.readSequence) throw new PluginFailure("STALE_SEQUENCE", "PTY read sequence is stale.");
                try {
                    InputStream input = pty.command.getInputStream();
                    long deadline = System.currentTimeMillis() + waitMs;
                    while (input.available() == 0 && !pty.command.isEOF() && pty.connection.state.equals("connected") && System.currentTimeMillis() < deadline) {
                        Thread.sleep(Math.min(20L, Math.max(1L, deadline - System.currentTimeMillis())));
                    }
                    int available = Math.min(maxBytes, Math.max(0, input.available()));
                    if (available > 0) {
                        byte[] buffer = new byte[available];
                        int count = input.read(buffer, 0, available);
                        resultBytes = count > 0 ? trim(buffer, count) : new byte[0];
                    } else {
                        resultBytes = new byte[0];
                    }
                    if (resultBytes.length > 0) pty.readSequence++;
                    resultSequence = pty.readSequence;
                    eof = pty.command.isEOF() || !pty.connection.state.equals("connected");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new PluginFailure("CHANNEL_CLOSED", "PTY read was interrupted.", interrupted);
                } catch (IOException error) {
                    throw failureFor(error);
                }
            }
            return new JSObject()
                .put("requestId", requestId)
                .put("connectionId", pty.connection.connectionId)
                .put("generationId", pty.connection.generationId)
                .put("channelId", pty.channelId)
                .put("sequence", resultSequence)
                .put("dataBase64", Base64.encodeToString(resultBytes, Base64.NO_WRAP))
                .put("eof", eof);
        });
    }

    @PluginMethod
    public void writePty(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshPty pty = requirePty(options);
            int sequence = boundedInt(options, "sequence", 1, Integer.MAX_VALUE, 1);
            String encoded = requiredString(options, "dataBase64");
            byte[] bytes;
            try {
                bytes = Base64.decode(encoded, Base64.DEFAULT);
            } catch (IllegalArgumentException error) {
                throw new PluginFailure("INVALID_ARGUMENT", "PTY input is not valid base64.", error);
            }
            if (bytes.length > MAX_PTY_WRITE_BYTES) throw new PluginFailure("INVALID_ARGUMENT", "PTY input exceeds 32 KiB.");
            synchronized (pty.operationLock) {
                requireNextOperation(pty, sequence);
                try {
                    OutputStream output = pty.command.getOutputStream();
                    output.write(bytes);
                    output.flush();
                    pty.operationSequence = sequence;
                } catch (IOException error) {
                    throw failureFor(error);
                }
            }
            return operationAck(requestId, pty, sequence);
        });
    }

    @PluginMethod
    public void resizePty(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshPty pty = requirePty(options);
            int sequence = boundedInt(options, "sequence", 1, Integer.MAX_VALUE, 1);
            int cols = boundedInt(options, "cols", 1, 1000, 80);
            int rows = boundedInt(options, "rows", 1, 1000, 24);
            synchronized (pty.operationLock) {
                requireNextOperation(pty, sequence);
                try {
                    pty.channel.changeWindowDimensions(cols, rows, 0, 0);
                    pty.operationSequence = sequence;
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return operationAck(requestId, pty, sequence);
        });
    }

    @PluginMethod
    public void closePty(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            String channelId = requiredString(options, "channelId");
            SshPty pty = ptys.get(channelId);
            if (pty != null) {
                verifyOwner(options, pty.connection);
                closePty(pty);
            }
            return ack(requestId);
        });
    }

    @PluginMethod
    public void sftpList(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String path = requiredString(options, "path");
            SFTPClient client = requireSftp(connection);
            JSObject result = new JSObject().put("requestId", requestId);
            org.json.JSONArray entries = new org.json.JSONArray();
            synchronized (connection.sftpLock) {
                try {
                    List<RemoteResourceInfo> listing = client.ls(path);
                    int count = Math.min(MAX_SFTP_ENTRIES, listing.size());
                    for (int index = 0; index < count; index++) {
                        RemoteResourceInfo info = listing.get(index);
                        FileAttributes attributes = info.getAttributes();
                        entries.put(new JSObject()
                            .put("path", info.getPath())
                            .put("name", info.getName())
                            .put("isDirectory", info.isDirectory())
                            .put("sizeBytes", attributes.getSize())
                            .put("modifiedEpochMs", attributes.getMtime() * 1000L));
                    }
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return result.put("entries", entries);
        });
    }

    @PluginMethod
    public void sftpRead(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String path = requiredString(options, "path");
            int maxBytes = boundedInt(options, "maxBytes", 0, MAX_SFTP_TRANSFER_BYTES, MAX_SFTP_TRANSFER_BYTES);
            SFTPClient client = requireSftp(connection);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            synchronized (connection.sftpLock) {
                try (RemoteFile file = client.open(path, Collections.singleton(OpenMode.READ))) {
                    byte[] block = new byte[Math.min(8192, Math.max(1, maxBytes))];
                    long offset = 0;
                    while (bytes.size() < maxBytes) {
                        int limit = Math.min(block.length, maxBytes - bytes.size());
                        int count = file.read(offset, block, 0, limit);
                        if (count <= 0) break;
                        bytes.write(block, 0, count);
                        offset += count;
                    }
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return new JSObject()
                .put("requestId", requestId)
                .put("dataBase64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        });
    }

    @PluginMethod
    public void sftpWrite(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String path = requiredString(options, "path");
            String encoded = requiredString(options, "dataBase64");
            byte[] bytes;
            try {
                bytes = Base64.decode(encoded, Base64.DEFAULT);
            } catch (IllegalArgumentException error) {
                throw new PluginFailure("INVALID_ARGUMENT", "SFTP content is not valid base64.", error);
            }
            if (bytes.length > MAX_SFTP_TRANSFER_BYTES) throw new PluginFailure("INVALID_ARGUMENT", "SFTP write exceeds 512 KiB.");
            SFTPClient client = requireSftp(connection);
            synchronized (connection.sftpLock) {
                try (RemoteFile file = client.open(path, new HashSet<>(Arrays.asList(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)))) {
                    file.write(0, bytes, 0, bytes.length);
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return ack(requestId).put("bytesWritten", bytes.length);
        });
    }

    @PluginMethod
    public void sftpMkdir(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            SFTPClient client = requireSftp(connection);
            synchronized (connection.sftpLock) {
                try {
                    client.mkdir(requiredString(options, "path"));
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return ack(requestId);
        });
    }

    @PluginMethod
    public void sftpRename(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            SFTPClient client = requireSftp(connection);
            synchronized (connection.sftpLock) {
                try {
                    client.rename(requiredString(options, "path"), requiredString(options, "destination"));
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return ack(requestId);
        });
    }

    @PluginMethod
    public void sftpDelete(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String path = requiredString(options, "path");
            SFTPClient client = requireSftp(connection);
            synchronized (connection.sftpLock) {
                try {
                    FileAttributes attributes = client.statExistence(path);
                    if (attributes != null && attributes.getType() == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
                        client.rmdir(path);
                    } else {
                        client.rm(path);
                    }
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return ack(requestId);
        });
    }

    @PluginMethod
    public void openPortForward(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String remoteHost = requiredString(options, "remoteHost");
            int remotePort = boundedInt(options, "remotePort", 1, 65535, 22);
            int requestedPort = boundedInt(options, "localPort", 0, 65535, 0);
            acquireChannel(connection);
            if (!FORWARD_PERMITS.tryAcquire()) {
                connection.channelPermits.release();
                throw new PluginFailure("FORWARD_LIMIT", "The app already has eight active local forwards.");
            }
            ServerSocket server = null;
            LocalPortForwarder forwarder = null;
            SshForward forwarding = null;
            try {
                server = new ServerSocket();
                server.setReuseAddress(false);
                server.bind(new InetSocketAddress("127.0.0.1", requestedPort));
                int localPort = server.getLocalPort();
                Parameters parameters = new Parameters(remoteHost, remotePort, "127.0.0.1", localPort);
                forwarder = connection.client.newLocalPortForwarder(parameters, server);
                forwarding = new SshForward(UUID.randomUUID().toString(), connection, server, forwarder, localPort);
                forwards.put(forwarding.forwardId, forwarding);
                LocalPortForwarder activeForwarder = forwarder;
                SshForward activeForwarding = forwarding;
                FORWARD_EXECUTOR.execute(() -> {
                    try {
                        activeForwarder.listen();
                    } catch (IOException error) {
                        if (!activeForwarding.closed.get() && connection.state.equals("connected")) {
                            activeForwarding.failure = error.getClass().getSimpleName();
                        }
                    } finally {
                        closeForward(activeForwarding);
                    }
                });
                return new JSObject()
                    .put("requestId", requestId)
                    .put("connectionId", connection.connectionId)
                    .put("generationId", connection.generationId)
                    .put("forwardId", forwarding.forwardId)
                    .put("localPort", localPort);
            } catch (Exception error) {
                if (forwarding != null) {
                    closeForward(forwarding);
                } else {
                    connection.channelPermits.release();
                    FORWARD_PERMITS.release();
                }
                if (forwarding == null && forwarder != null) {
                    try {
                        forwarder.close();
                    } catch (Exception ignored) {}
                }
                closeQuietly(server);
                throw failureFor(error);
            }
        });
    }

    @PluginMethod
    public void closePortForward(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            String forwardId = requiredString(options, "forwardId");
            SshForward forward = forwards.get(forwardId);
            if (forward != null) {
                verifyOwner(options, forward.connection);
                closeForward(forward);
            }
            return ack(requestId);
        });
    }

    @PluginMethod
    public void resourceSnapshot(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            return new JSObject()
                .put("requestId", requestId)
                .put("connections", connections.size())
                .put("ptys", ptys.size())
                .put("sftpClients", countSftpClients())
                .put("forwards", forwards.size());
        });
    }

    private void run(PluginCall call, CallOperation operation) {
        try {
            JSObject result = operation.execute(call.getData());
            call.resolve(result);
        } catch (PluginFailure failure) {
            call.reject(failure.getMessage(), failure.code, failure, failure.data);
        } catch (Exception error) {
            PluginFailure failure = failureFor(error);
            call.reject(failure.getMessage(), failure.code, failure, failure.data);
        }
    }

    private SshConnection requireConnection(JSObject options) throws PluginFailure {
        SshConnection connection = findConnection(options);
        if (connection == null) throw new PluginFailure("CONNECTION_CLOSED", "SSH connection is closed.");
        if (!connection.state.equals("connected") || !connection.client.isConnected()) {
            connection.state = "lost";
            throw new PluginFailure("CONNECTION_LOST", "SSH connection is no longer available.");
        }
        return connection;
    }

    private SshConnection findConnection(JSObject options) throws PluginFailure {
        String connectionId = requiredString(options, "connectionId");
        String generationId = requiredString(options, "generationId");
        SshConnection connection = connections.get(connectionId);
        if (connection != null && !connection.generationId.equals(generationId)) {
            throw new PluginFailure("STALE_GENERATION", "SSH operation belongs to a stale connection generation.");
        }
        return connection;
    }

    private void verifyOwner(JSObject options, SshConnection expected) throws PluginFailure {
        SshConnection actual = findConnection(options);
        if (actual != expected) throw new PluginFailure("STALE_GENERATION", "SSH resource belongs to a stale connection generation.");
    }

    private SshPty requirePty(JSObject options) throws PluginFailure {
        SshConnection connection = requireConnection(options);
        String channelId = requiredString(options, "channelId");
        SshPty pty = ptys.get(channelId);
        if (pty == null || pty.connection != connection) throw new PluginFailure("CHANNEL_CLOSED", "PTY channel is closed.");
        return pty;
    }

    private SFTPClient requireSftp(SshConnection connection) throws PluginFailure {
        synchronized (connection.sftpLock) {
            if (connection.sftpClient != null) return connection.sftpClient;
            try {
                connection.sftpClient = connection.client.newSFTPClient();
                return connection.sftpClient;
            } catch (Exception error) {
                throw failureFor(error);
            }
        }
    }

    private void acquireChannel(SshConnection connection) throws PluginFailure {
        if (!connection.channelPermits.tryAcquire()) {
            throw new PluginFailure("CHANNEL_LIMIT", "This SSH connection already has eight active channels.");
        }
        if (!connection.state.equals("connected")) {
            connection.channelPermits.release();
            throw new PluginFailure("CONNECTION_LOST", "SSH connection is no longer available.");
        }
    }

    private void closeConnection(SshConnection connection, String reason, boolean notify) {
        if (!connection.intentionalClose.compareAndSet(false, true)) return;
        synchronized (connection.graceLock) {
            if (connection.graceRunnable != null) mainHandler.removeCallbacks(connection.graceRunnable);
            connection.graceRunnable = null;
            connection.graceDeadlineEpochMs = null;
        }
        connection.state = "closed";
        closeChildren(connection);
        connections.remove(connection.connectionId, connection);
        closeClient(connection.client);
        if (notify) {
            JSObject event = new JSObject()
                .put("connectionId", connection.connectionId)
                .put("generationId", connection.generationId)
                .put("state", "closed")
                .put("reason", reason);
            mainHandler.post(() -> notifyListeners("connectionState", event));
        }
    }

    private void closeChildren(SshConnection connection) {
        for (SshPty pty : new ArrayList<>(ptys.values())) {
            if (pty.connection == connection) closePty(pty);
        }
        for (SshForward forward : new ArrayList<>(forwards.values())) {
            if (forward.connection == connection) closeForward(forward);
        }
        synchronized (connection.sftpLock) {
            SFTPClient client = connection.sftpClient;
            connection.sftpClient = null;
            closeQuietly(client);
        }
    }

    private void closePty(SshPty pty) {
        if (!pty.closed.compareAndSet(false, true)) return;
        ptys.remove(pty.channelId, pty);
        closeQuietly(pty.channel);
        pty.connection.channelPermits.release();
    }

    private void closeForward(SshForward forward) {
        if (!forward.closed.compareAndSet(false, true)) return;
        forwards.remove(forward.forwardId, forward);
        try {
            forward.forwarder.close();
        } catch (Exception ignored) {}
        closeQuietly(forward.serverSocket);
        forward.connection.channelPermits.release();
        FORWARD_PERMITS.release();
    }

    private int countSftpClients() {
        int count = 0;
        for (SshConnection connection : connections.values()) {
            synchronized (connection.sftpLock) {
                if (connection.sftpClient != null) count++;
            }
        }
        return count;
    }

    private static JSObject ack(String requestId) {
        return new JSObject().put("requestId", requestId);
    }

    private static JSObject operationAck(String requestId, SshPty pty, int sequence) {
        return new JSObject()
            .put("requestId", requestId)
            .put("connectionId", pty.connection.connectionId)
            .put("generationId", pty.connection.generationId)
            .put("channelId", pty.channelId)
            .put("sequence", sequence);
    }

    private static void requireNextOperation(SshPty pty, int sequence) throws PluginFailure {
        if (sequence != pty.operationSequence + 1) throw new PluginFailure("STALE_SEQUENCE", "PTY operation sequence is stale.");
    }

    private static int boundedInt(JSObject options, String name, int min, int max, int defaultValue) throws PluginFailure {
        Integer value = options.getInteger(name);
        int resolved = value == null ? defaultValue : value;
        if (resolved < min || resolved > max) throw new PluginFailure("INVALID_ARGUMENT", name + " is outside its supported range.");
        return resolved;
    }

    private static long requiredLong(JSObject options, String name) throws PluginFailure {
        try {
            return options.getLong(name);
        } catch (Exception error) {
            throw new PluginFailure("INVALID_ARGUMENT", name + " must be an integer.");
        }
    }

    private static String requiredString(JSObject options, String name) throws PluginFailure {
        String value = options.getString(name);
        if (value == null || value.isBlank()) throw new PluginFailure("INVALID_ARGUMENT", name + " is required.");
        return value;
    }

    private static byte[] trim(byte[] data, int length) {
        if (length == data.length) return data;
        byte[] trimmed = new byte[length];
        System.arraycopy(data, 0, trimmed, 0, length);
        return trimmed;
    }

    private static Thread streamThread(InputStream input, BoundedCapture output, String label) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = input.read(buffer)) >= 0) output.append(buffer, 0, read);
            } catch (IOException ignored) {
                // Closing a timed-out command also closes its streams.
            }
        }, "pocketshell-ssh-exec-" + label);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinReader(Thread reader) {
        if (reader == null) return;
        try {
            reader.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeClient(SSHClient client) {
        try {
            client.setTimeout(1000);
            client.disconnect();
        } catch (Exception ignored) {}
        try {
            client.close();
        } catch (Exception ignored) {}
    }

    private static void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception ignored) {}
    }

    private static PluginFailure failureFor(Exception error) {
        if (error instanceof PluginFailure) return (PluginFailure) error;
        String code = "SSH_IO";
        if (error.getClass().getSimpleName().toLowerCase().contains("auth")) code = "AUTH_FAILED";
        else if (error instanceof IllegalArgumentException) code = "INVALID_ARGUMENT";
        else if (error instanceof IOException) code = "CONNECTION_LOST";
        return new PluginFailure(code, safeMessage(error), error);
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "SSH operation failed.";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private interface CallOperation {
        JSObject execute(JSObject options) throws Exception;
    }

    private static final class PluginFailure extends Exception {
        final String code;
        final JSObject data;

        PluginFailure(String code, String message) {
            this(code, message, null, null);
        }

        PluginFailure(String code, String message, Exception cause) {
            this(code, message, null, cause);
        }

        PluginFailure(String code, String message, JSObject data, Exception cause) {
            super(message, cause);
            this.code = code;
            this.data = data;
        }
    }

    private static final class PresentedHostKey {
        String keyType;
        String keyB64;
        String fingerprintSha256;
        boolean trusted;

        JSObject asJson() {
            return new JSObject()
                .put("keyType", keyType)
                .put("keyB64", keyB64)
                .put("fingerprintSha256", fingerprintSha256);
        }
    }

    private static final class PinVerifier implements HostKeyVerifier {
        private final String expectedKeyType;
        private final String expectedKeyB64;
        private final PresentedHostKey presented;

        PinVerifier(String expectedKeyType, String expectedKeyB64, PresentedHostKey presented) {
            this.expectedKeyType = expectedKeyType;
            this.expectedKeyB64 = expectedKeyB64;
            this.presented = presented;
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            try {
                byte[] blob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
                Buffer.PlainBuffer reader = new Buffer.PlainBuffer(blob);
                String keyType = reader.readString();
                String keyB64 = Base64.encodeToString(blob, Base64.NO_WRAP);
                String fingerprint = "SHA256:" + Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(blob),
                    Base64.NO_WRAP
                ).replaceAll("=+$", "");
                presented.keyType = keyType;
                presented.keyB64 = keyB64;
                presented.fingerprintSha256 = fingerprint;
                presented.trusted = expectedKeyType != null
                    && expectedKeyB64 != null
                    && expectedKeyType.equals(keyType)
                    && expectedKeyB64.equals(keyB64);
                return presented.trusted;
            } catch (Exception error) {
                presented.keyType = "unknown";
                presented.keyB64 = "";
                presented.fingerprintSha256 = "SHA256:unavailable";
                presented.trusted = false;
                return false;
            }
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            return Collections.emptyList();
        }
    }

    private static final class SshConnection {
        final String connectionId;
        final String generationId;
        final String hostId;
        final SSHClient client;
        final Semaphore channelPermits = new Semaphore(MAX_CHANNELS_PER_CONNECTION);
        final AtomicBoolean intentionalClose = new AtomicBoolean(false);
        final Object sftpLock = new Object();
        final Object graceLock = new Object();
        volatile String state = "connected";
        volatile SFTPClient sftpClient;
        volatile Runnable graceRunnable;
        volatile Long graceDeadlineEpochMs;

        SshConnection(String connectionId, String generationId, String hostId, SSHClient client) {
            this.connectionId = connectionId;
            this.generationId = generationId;
            this.hostId = hostId;
            this.client = client;
        }
    }

    private static final class SshPty {
        final String channelId;
        final SshConnection connection;
        final SessionChannel channel;
        final Session.Command command;
        final Object readLock = new Object();
        final Object operationLock = new Object();
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile int readSequence;
        volatile int operationSequence;

        SshPty(String channelId, SshConnection connection, SessionChannel channel, Session.Command command) {
            this.channelId = channelId;
            this.connection = connection;
            this.channel = channel;
            this.command = command;
        }
    }

    private static final class SshForward {
        final String forwardId;
        final SshConnection connection;
        final ServerSocket serverSocket;
        final LocalPortForwarder forwarder;
        final int localPort;
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile String failure;

        SshForward(String forwardId, SshConnection connection, ServerSocket serverSocket, LocalPortForwarder forwarder, int localPort) {
            this.forwardId = forwardId;
            this.connection = connection;
            this.serverSocket = serverSocket;
            this.forwarder = forwarder;
            this.localPort = localPort;
        }
    }

    private static final class BoundedCapture {
        private final int maxBytes;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private volatile boolean truncated;

        BoundedCapture(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized void append(byte[] bytes, int offset, int length) {
            int room = Math.max(0, maxBytes - captured.size());
            int accepted = Math.min(room, length);
            if (accepted > 0) captured.write(bytes, offset, accepted);
            if (accepted < length) truncated = true;
        }

        synchronized String asUtf8() {
            return new String(captured.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
