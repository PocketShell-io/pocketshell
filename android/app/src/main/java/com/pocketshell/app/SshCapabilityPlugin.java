package com.pocketshell.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
import net.schmizz.sshj.sftp.Response;
import net.schmizz.sshj.sftp.SFTPException;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyFormat;
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.SecurityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Narrow Android SSH I/O capability. This class owns sshj objects and bounded
 * channel/resource cleanup. Session commands, trust decisions, reconnect and
 * background grace policy remain in TypeScript.
 */
@CapacitorPlugin(name = "SshCapability")
public final class SshCapabilityPlugin extends Plugin {
    private static final String LOCAL_FORWARD_BIND_HOST = "127.0.0.1";
    private static final int MAX_CHANNELS_PER_CONNECTION = 8;
    private static final int MAX_PTY_READ_BYTES = 32 * 1024;
    private static final int MAX_PTY_WRITE_BYTES = 32 * 1024;
    private static final int MAX_SFTP_TRANSFER_BYTES = 512 * 1024;
    private static final int MAX_EXEC_COMMAND_CHARS = 32 * 1024;
    private static final int MAX_EXEC_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_EXEC_TIMEOUT_MS = 120_000;
    private static final int MAX_SFTP_ENTRIES = 1000;
    private static final int MAX_ACTIVE_FORWARDS = 8;
    private static final int MAX_CONNECT_CANCELLATION_TOMBSTONES = 256;
    private static final long CONNECT_CANCELLATION_TTL_MS = 120_000L;
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
    private static volatile boolean sshProviderReady;

    private final Map<String, SshConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, SshPty> ptys = new ConcurrentHashMap<>();
    private final Map<String, SshForward> forwards = new ConcurrentHashMap<>();
    private final Object connectLock = new Object();
    private final Map<String, ConnectAttempt> pendingConnects = new HashMap<>();
    private final LinkedHashMap<String, Long> connectCancellationTombstones = new LinkedHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void handleOnDestroy() {
        cancelPendingConnects();
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
            String pem = null;
            if ("private-key".equals(credentialKind)) {
                pem = requiredString(credential, "privateKeyPem");
            } else if ("legacy-private-key".equals(credentialKind)) {
                long keyId = requiredLong(credential, "keyId");
                String keySha256 = requiredString(credential, "sha256");
                try {
                    pem = LegacyPrivateKeyResolver.readPrivateKey(getContext(), keyId, keySha256);
                } catch (IOException error) {
                    throw new PluginFailure("INVALID_ARGUMENT", error.getMessage(), error);
                }
            } else if (!"password".equals(credentialKind)) {
                throw new PluginFailure("INVALID_ARGUMENT", "SSH credential kind is not supported.");
            }
            HostKeyPinExpectation expectedHostKey = parseExpectedHostKey(options.getJSObject("expectedHostKey"));

            ensureSshCryptoProvider();
            SSHClient client = new SSHClient();
            ConnectAttempt attempt = new ConnectAttempt(requestId, client);
            PresentedHostKey presented = new PresentedHostKey();
            SshConnection connection = null;
            try {
                registerConnectAttempt(attempt);
                checkConnectNotCancelled(attempt);
                client.setConnectTimeout(connectTimeout);
                client.setTimeout(Math.min(connectTimeout, 10_000));
                client.addHostKeyVerifier(new PinVerifier(expectedHostKey, presented, attempt));
                client.connect(hostname, port);
                checkConnectNotCancelled(attempt);
                client.setTimeout(5000);
                if ("password".equals(credentialKind)) {
                    String password = requiredString(credential, "password");
                    char[] secret = password.toCharArray();
                    try {
                        client.authPassword(username, secret);
                    } finally {
                        PasswordUtils.blankOut(secret);
                    }
                } else if ("private-key".equals(credentialKind) || "legacy-private-key".equals(credentialKind)) {
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
                checkConnectNotCancelled(attempt);

                connection = new SshConnection(UUID.randomUUID().toString(), generationId, hostId, requestId, client);
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
                synchronized (connectLock) {
                    checkConnectNotCancelled(attempt);
                    if (!connection.state.equals("connected") || !client.isConnected()) {
                        throw new PluginFailure("CONNECTION_LOST", "SSH connection ended during setup.");
                    }
                    connections.put(connection.connectionId, connection);
                    pendingConnects.remove(requestId, attempt);
                }
                JSObject hostKey = presented.asJson();
                return new JSObject()
                    .put("requestId", requestId)
                    .put("connectionId", connection.connectionId)
                    .put("generationId", generationId)
                    .put("hostKey", hostKey);
            } catch (Exception error) {
                if (attempt.cancelled.get()) {
                    if (connection != null) closeConnection(connection, "connect-cancelled", false);
                    else attempt.closeClient();
                    throw new PluginFailure("CANCELLED", "SSH connection attempt was cancelled.", error);
                }
                if (connection != null) closeConnection(connection, "connect-failed", false);
                else attempt.closeClient();
                if (presented.keyType != null && !presented.trusted) {
                    JSObject details = presented.asJson();
                    throw new PluginFailure("HOST_KEY_REJECTED", "The SSH host key has not been trusted.", details, error);
                }
                throw failureFor(error);
            } finally {
                unregisterConnectAttempt(attempt);
            }
        });
    }

    @PluginMethod
    public void cancelOperation(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            JSObject target = options.getJSObject("target");
            if (target == null) throw new PluginFailure("INVALID_ARGUMENT", "SSH cancellation target is missing.");
            String kind = requiredString(target, "kind");
            boolean cancelled;
            if ("connect".equals(kind)) {
                String targetRequestId = requiredString(target, "targetRequestId");
                cancelConnect(targetRequestId);
                cancelled = true;
            } else if ("connection".equals(kind)) {
                SshConnection connection = findConnection(target);
                cancelled = connection != null;
                if (connection != null) closeConnection(connection, "operation-cancelled", false);
            } else {
                throw new PluginFailure("INVALID_ARGUMENT", "SSH cancellation target kind is not supported.");
            }
            return ack(requestId).put("cancelled", cancelled);
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
                long scheduledAtEpochMs = System.currentTimeMillis();
                long scheduledAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
                long delayMs = Math.max(0L, deadline - scheduledAtEpochMs);
                connection.graceScheduledAtEpochMs = scheduledAtEpochMs;
                connection.graceScheduledAtElapsedRealtimeMs = scheduledAtElapsedRealtimeMs;
                connection.graceDeadlineElapsedRealtimeMs = scheduledAtElapsedRealtimeMs + delayMs;
                connection.graceExpiryDispatchedAtEpochMs = null;
                connection.graceExpiryDispatchedAtElapsedRealtimeMs = null;
                connection.graceCleanupExecutorRejectErrorClass = "";
                Runnable[] holder = new Runnable[1];
                Runnable closeAtDeadline = () -> {
                    synchronized (connection.graceLock) {
                        if (connection.graceRunnable != holder[0]) return;
                        connection.graceRunnable = null;
                        connection.graceExpiryDispatchedAtEpochMs = System.currentTimeMillis();
                        connection.graceExpiryDispatchedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
                    }
                    Runnable cleanup = () -> closeConnection(connection, "grace-expired", true);
                    try {
                        CLEANUP_EXECUTOR.execute(cleanup);
                    } catch (RejectedExecutionException rejected) {
                        connection.graceCleanupExecutorRejectErrorClass = rejected.getClass().getSimpleName();
                        Thread fallback = new Thread(cleanup, "pocketshell-ssh-cleanup-fallback");
                        fallback.setDaemon(true);
                        fallback.start();
                    }
                };
                holder[0] = closeAtDeadline;
                connection.graceRunnable = closeAtDeadline;
                connection.graceDeadlineEpochMs = deadline;
                if (!mainHandler.postDelayed(closeAtDeadline, delayMs)) {
                    connection.graceRunnable = null;
                    connection.graceDeadlineEpochMs = null;
                    connection.graceScheduledAtEpochMs = null;
                    connection.graceScheduledAtElapsedRealtimeMs = null;
                    connection.graceDeadlineElapsedRealtimeMs = null;
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
                        connection.graceScheduledAtEpochMs = null;
                        connection.graceScheduledAtElapsedRealtimeMs = null;
                        connection.graceDeadlineElapsedRealtimeMs = null;
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
            String rootPath = optionalString(options, "rootPath");
            SFTPClient client = requireSftp(connection);
            JSObject result = new JSObject().put("requestId", requestId);
            org.json.JSONArray entries = new org.json.JSONArray();
            synchronized (connection.sftpLock) {
                try {
                    String listingPath = path;
                    if (rootPath != null) {
                        ResolvedSftpPath resolved = resolveSftpPath(client, rootPath, path, false);
                        requireSftpType(resolved.attributes, net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY, "SFTP path is not a directory.");
                        listingPath = resolved.path;
                    }
                    List<RemoteResourceInfo> listing = client.ls(listingPath);
                    int count = Math.min(MAX_SFTP_ENTRIES, listing.size());
                    for (int index = 0; index < count; index++) {
                        RemoteResourceInfo info = listing.get(index);
                        FileAttributes attributes = info.getAttributes();
                        String type = sftpEntryType(attributes.getType());
                        entries.put(new JSObject()
                            .put("path", info.getPath())
                            .put("name", info.getName())
                            .put("type", type)
                            .put("isDirectory", attributes.getType() == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
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
            String rootPath = optionalString(options, "rootPath");
            int maxBytes = boundedInt(options, "maxBytes", 0, MAX_SFTP_TRANSFER_BYTES, MAX_SFTP_TRANSFER_BYTES);
            SFTPClient client = requireSftp(connection);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            synchronized (connection.sftpLock) {
                try {
                    String readPath = path;
                    if (rootPath != null) {
                        ResolvedSftpPath resolved = resolveSftpPath(client, rootPath, path, false);
                        requireSftpType(resolved.attributes, net.schmizz.sshj.sftp.FileMode.Type.REGULAR, "SFTP path is not a regular file.");
                        readPath = resolved.path;
                    }
                    try (RemoteFile file = client.open(readPath, Collections.singleton(OpenMode.READ))) {
                        byte[] block = new byte[Math.min(8192, Math.max(1, maxBytes))];
                        long offset = 0;
                        while (bytes.size() < maxBytes) {
                            int limit = Math.min(block.length, maxBytes - bytes.size());
                            int count = file.read(offset, block, 0, limit);
                            if (count <= 0) break;
                            bytes.write(block, 0, count);
                            offset += count;
                        }
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
            String rootPath = optionalString(options, "rootPath");
            boolean createOnly = Boolean.TRUE.equals(options.getBool("createOnly"));
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
                try {
                    String writePath = path;
                    if (rootPath != null) {
                        ResolvedSftpPath resolved = resolveSftpPath(client, rootPath, path, true);
                        if (resolved.exists) {
                            requireSftpType(resolved.attributes, net.schmizz.sshj.sftp.FileMode.Type.REGULAR, "SFTP write target is not a regular file.");
                            if (createOnly) throw new PluginFailure("SFTP_FILE_EXISTS", "SFTP write target already exists.");
                        }
                        writePath = resolved.path;
                    }
                    Set<OpenMode> modes = new HashSet<>(Arrays.asList(OpenMode.WRITE, OpenMode.CREAT));
                    modes.add(createOnly ? OpenMode.EXCL : OpenMode.TRUNC);
                    try (RemoteFile file = client.open(writePath, modes)) {
                        file.write(0, bytes, 0, bytes.length);
                    }
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return ack(requestId).put("bytesWritten", bytes.length);
        });
    }

    @PluginMethod
    public void sftpWriteIfUnchanged(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String path = requiredString(options, "path");
            String rootPath = requiredString(options, "rootPath");
            JSObject expected = options.getJSObject("expectedMetadata");
            if (expected == null || !Boolean.FALSE.equals(expected.getBool("isDirectory"))) {
                throw new PluginFailure("INVALID_ARGUMENT", "Expected file metadata is missing or invalid.");
            }
            long expectedSize = requiredLong(expected, "sizeBytes");
            long expectedMtime = requiredLong(expected, "modifiedEpochMs");
            if (expectedSize < 0 || expectedMtime <= 0) {
                throw new PluginFailure("INVALID_ARGUMENT", "Expected file metadata is outside its supported range.");
            }
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
                try {
                    ResolvedSftpPath resolved = resolveSftpPath(client, rootPath, path, true);
                    if (!resolved.exists) return sftpWriteConflict(requestId, "missing");
                    FileAttributes current = resolved.attributes;
                    if (current.getType() == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK) {
                        throw new PluginFailure("SFTP_SYMLINK", "SFTP file operations do not follow symbolic links.");
                    }
                    if (current.getType() != net.schmizz.sshj.sftp.FileMode.Type.REGULAR) {
                        return sftpWriteConflict(requestId, "changed");
                    }
                    // All PocketShell SFTP operations on this connection share this lock. Open
                    // without truncating, validate the opened inode, then check and mutate
                    // through the same native file handle in this one bridge call.
                    try (RemoteFile file = client.open(resolved.path, new HashSet<>(Arrays.asList(OpenMode.READ, OpenMode.WRITE)))) {
                        FileAttributes opened = file.fetchAttributes();
                        if (opened.getType() != net.schmizz.sshj.sftp.FileMode.Type.REGULAR
                            || opened.getSize() != expectedSize
                            || opened.getMtime() * 1000L != expectedMtime) {
                            return sftpWriteConflict(requestId, "changed");
                        }
                        file.setLength(0);
                        file.write(0, bytes, 0, bytes.length);
                    }
                } catch (Exception error) {
                    throw failureFor(error);
                }
            }
            return new JSObject()
                .put("requestId", requestId)
                .put("status", "written")
                .put("bytesWritten", bytes.length);
        });
    }

    @PluginMethod
    public void sftpMkdir(PluginCall call) {
        run(call, options -> {
            String requestId = requiredString(options, "requestId");
            SshConnection connection = requireConnection(options);
            String path = requiredString(options, "path");
            String rootPath = optionalString(options, "rootPath");
            SFTPClient client = requireSftp(connection);
            synchronized (connection.sftpLock) {
                try {
                    String directoryPath = path;
                    if (rootPath != null) {
                        ResolvedSftpPath resolved = resolveSftpPath(client, rootPath, path, true);
                        if (resolved.exists) throw new PluginFailure("SFTP_FILE_EXISTS", "SFTP directory path already exists.");
                        directoryPath = resolved.path;
                    }
                    client.mkdir(directoryPath);
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

    private static JSObject sftpWriteConflict(String requestId, String verdict) {
        return new JSObject()
            .put("requestId", requestId)
            .put("status", "conflict")
            .put("verdict", verdict);
    }

    private static String optionalString(JSObject options, String name) throws PluginFailure {
        String value = options.getString(name);
        if (value == null) return null;
        if (value.isBlank()) throw new PluginFailure("INVALID_ARGUMENT", name + " must not be blank.");
        return value;
    }

    /** Resolve a file-workspace path beneath its server-resolved root. */
    private static ResolvedSftpPath resolveSftpPath(
        SFTPClient client,
        String rootPath,
        String path,
        boolean allowMissingLeaf
    ) throws Exception {
        validateAbsoluteSftpPath(rootPath, "rootPath");
        validateAbsoluteSftpPath(path, "path");
        String canonicalRoot = normalizeCanonicalSftpPath(client.canonicalize(rootPath));
        FileAttributes rootAttributes = client.lstat(canonicalRoot);
        if (rootAttributes.getType() != net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
            throw new PluginFailure("SFTP_NOT_DIRECTORY", "The configured SFTP workspace root is not a directory.");
        }

        FileAttributes attributes = lstatOrNull(client, path);
        if (attributes != null) {
            if (attributes.getType() == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK) {
                throw new PluginFailure("SFTP_SYMLINK", "SFTP file operations do not follow symbolic links.");
            }
            String canonicalPath = normalizeCanonicalSftpPath(client.canonicalize(path));
            requireSftpPathWithinRoot(canonicalRoot, canonicalPath);
            return new ResolvedSftpPath(canonicalPath, attributes, true);
        }

        if (!allowMissingLeaf) {
            throw new PluginFailure("SFTP_NOT_FOUND", "The SFTP path does not exist.");
        }
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? "" : path.substring(slash + 1);
        String parent = slash <= 0 ? "/" : path.substring(0, slash);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new PluginFailure("INVALID_ARGUMENT", "An SFTP write path must name a child entry.");
        }
        FileAttributes parentAttributes = lstatOrNull(client, parent);
        if (parentAttributes == null) {
            throw new PluginFailure("SFTP_NOT_FOUND", "The SFTP parent directory does not exist.");
        }
        if (parentAttributes.getType() == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK) {
            throw new PluginFailure("SFTP_SYMLINK", "SFTP file operations do not follow symbolic links.");
        }
        if (parentAttributes.getType() != net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
            throw new PluginFailure("SFTP_NOT_DIRECTORY", "The SFTP parent path is not a directory.");
        }
        String canonicalParent = normalizeCanonicalSftpPath(client.canonicalize(parent));
        requireSftpPathWithinRoot(canonicalRoot, canonicalParent);
        String canonicalPath = canonicalParent.equals("/") ? "/" + name : canonicalParent + "/" + name;
        requireSftpPathWithinRoot(canonicalRoot, canonicalPath);
        return new ResolvedSftpPath(canonicalPath, null, false);
    }

    private static FileAttributes lstatOrNull(SFTPClient client, String path) throws IOException {
        try {
            return client.lstat(path);
        } catch (SFTPException error) {
            Response.StatusCode status = error.getStatusCode();
            if (status == Response.StatusCode.NO_SUCH_FILE || status == Response.StatusCode.NO_SUCH_PATH) return null;
            throw error;
        }
    }

    private static void validateAbsoluteSftpPath(String path, String name) throws PluginFailure {
        if (path == null || path.isBlank() || !path.startsWith("/") || path.indexOf('\0') >= 0) {
            throw new PluginFailure("INVALID_ARGUMENT", name + " must be an absolute remote path.");
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new PluginFailure("INVALID_ARGUMENT", name + " must not contain dot path segments.");
            }
        }
    }

    private static String normalizeCanonicalSftpPath(String path) throws PluginFailure {
        validateAbsoluteSftpPath(path, "canonical path");
        String normalized = path.replaceAll("/{2,}", "/");
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void requireSftpPathWithinRoot(String rootPath, String path) throws PluginFailure {
        boolean within = rootPath.equals("/")
            ? path.startsWith("/")
            : path.equals(rootPath) || path.startsWith(rootPath + "/");
        if (!within) {
            throw new PluginFailure("SFTP_OUTSIDE_ROOT", "The resolved SFTP path is outside the configured workspace root.");
        }
    }

    private static void requireSftpType(
        FileAttributes attributes,
        net.schmizz.sshj.sftp.FileMode.Type expected,
        String message
    ) throws PluginFailure {
        if (attributes == null) throw new PluginFailure("SFTP_NOT_FOUND", "The SFTP path does not exist.");
        net.schmizz.sshj.sftp.FileMode.Type type = attributes.getType();
        if (type == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK) {
            throw new PluginFailure("SFTP_SYMLINK", "SFTP file operations do not follow symbolic links.");
        }
        if (type != expected) {
            String code = expected == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY
                ? "SFTP_NOT_DIRECTORY"
                : "SFTP_NOT_FILE";
            throw new PluginFailure(code, message);
        }
    }

    private static String sftpEntryType(net.schmizz.sshj.sftp.FileMode.Type type) {
        if (type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) return "directory";
        if (type == net.schmizz.sshj.sftp.FileMode.Type.REGULAR) return "file";
        if (type == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK) return "symlink";
        return "other";
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
                server.bind(new InetSocketAddress(LOCAL_FORWARD_BIND_HOST, requestedPort));
                int localPort = server.getLocalPort();
                Parameters parameters = localForwardParameters(remoteHost, remotePort, localPort);
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

    /** SSHJ parameters use local endpoint first, then the remote destination. */
    private static Parameters localForwardParameters(String remoteHost, int remotePort, int localPort) {
        return new Parameters(LOCAL_FORWARD_BIND_HOST, localPort, remoteHost, remotePort);
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

    private static HostKeyPinExpectation parseExpectedHostKey(JSObject expectedHostKey) throws PluginFailure {
        if (expectedHostKey == null) return null;
        String kind = expectedHostKey.getString("kind");
        if ("sha256-fingerprint".equals(kind)) {
            return new HostKeyPinExpectation(null, null, requiredString(expectedHostKey, "fingerprintSha256"));
        }
        if ("wire-key".equals(kind)) {
            return new HostKeyPinExpectation(
                requiredString(expectedHostKey, "keyType"),
                requiredString(expectedHostKey, "keyB64"),
                null
            );
        }
        // Accept the previous JS capability shape during a same-app upgrade.
        // Core now adapts persisted Android rows to the fingerprint form.
        if (kind == null) {
            return new HostKeyPinExpectation(
                requiredString(expectedHostKey, "keyType"),
                requiredString(expectedHostKey, "keyB64"),
                null
            );
        }
        throw new PluginFailure("INVALID_ARGUMENT", "SSH host-key pin kind is not supported.");
    }

    /**
     * Android installs a platform provider named BC which can shadow sshj's
     * bundled provider while lacking X25519. Install the bundled implementation
     * under BC before sshj caches that provider name; this keeps modern server
     * KEX available on Android releases with the older platform provider.
     */
    private static synchronized void ensureSshCryptoProvider() throws PluginFailure {
        if (sshProviderReady) return;
        try {
            Provider installed = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
            if (installed == null || installed.getService("KeyPairGenerator", "X25519") == null) {
                if (installed != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
                Security.insertProviderAt(new BouncyCastleProvider(), 1);
            }
            Provider selected = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
            if (selected == null || selected.getService("KeyPairGenerator", "X25519") == null) {
                throw new IllegalStateException("the installed BC provider does not offer X25519");
            }
            SecurityUtils.setRegisterBouncyCastle(false);
            SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME);
            sshProviderReady = true;
        } catch (RuntimeException error) {
            throw new PluginFailure("CRYPTO_UNAVAILABLE", "Android could not install the bundled SSH crypto provider.", error);
        }
    }

    private void registerConnectAttempt(ConnectAttempt attempt) throws PluginFailure {
        synchronized (connectLock) {
            pruneConnectCancellationTombstones();
            Long cancelledUntil = connectCancellationTombstones.remove(attempt.requestId);
            if (cancelledUntil != null && cancelledUntil >= System.currentTimeMillis()) {
                attempt.cancelled.set(true);
                throw new PluginFailure("CANCELLED", "SSH connection attempt was cancelled before it started.");
            }
            if (pendingConnects.containsKey(attempt.requestId)) {
                throw new PluginFailure("DUPLICATE_REQUEST", "SSH connect request id is already active.");
            }
            pendingConnects.put(attempt.requestId, attempt);
        }
    }

    private void unregisterConnectAttempt(ConnectAttempt attempt) {
        synchronized (connectLock) {
            pendingConnects.remove(attempt.requestId, attempt);
        }
    }

    private void checkConnectNotCancelled(ConnectAttempt attempt) throws PluginFailure {
        if (attempt.cancelled.get()) throw new PluginFailure("CANCELLED", "SSH connection attempt was cancelled.");
    }

    private void cancelConnect(String targetRequestId) {
        ConnectAttempt attempt;
        SshConnection connected = null;
        synchronized (connectLock) {
            pruneConnectCancellationTombstones();
            attempt = pendingConnects.get(targetRequestId);
            if (attempt != null) {
                attempt.cancelled.set(true);
            } else {
                for (SshConnection candidate : connections.values()) {
                    if (candidate.connectRequestId.equals(targetRequestId)) {
                        connected = candidate;
                        break;
                    }
                }
                if (connected == null) {
                    connectCancellationTombstones.put(
                        targetRequestId,
                        System.currentTimeMillis() + CONNECT_CANCELLATION_TTL_MS
                    );
                    pruneConnectCancellationTombstones();
                }
            }
        }
        if (attempt != null) attempt.closeClient();
        if (connected != null) closeConnection(connected, "connect-cancelled", false);
    }

    private void cancelPendingConnects() {
        List<ConnectAttempt> attempts;
        synchronized (connectLock) {
            attempts = new ArrayList<>(pendingConnects.values());
            for (ConnectAttempt attempt : attempts) attempt.cancelled.set(true);
            connectCancellationTombstones.clear();
        }
        for (ConnectAttempt attempt : attempts) attempt.closeClient();
    }

    private void pruneConnectCancellationTombstones() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = connectCancellationTombstones.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < now) iterator.remove();
        }
        while (connectCancellationTombstones.size() > MAX_CONNECT_CANCELLATION_TOMBSTONES) {
            Iterator<String> oldest = connectCancellationTombstones.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
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
        long closedAtEpochMs = System.currentTimeMillis();
        long closedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
        Long graceScheduledAtEpochMs = connection.graceScheduledAtEpochMs;
        Long graceScheduledAtElapsedRealtimeMs = connection.graceScheduledAtElapsedRealtimeMs;
        Long graceDeadlineEpochMs = connection.graceDeadlineEpochMs;
        Long graceDeadlineElapsedRealtimeMs = connection.graceDeadlineElapsedRealtimeMs;
        Long graceExpiryDispatchedAtEpochMs = connection.graceExpiryDispatchedAtEpochMs;
        Long graceExpiryDispatchedAtElapsedRealtimeMs = connection.graceExpiryDispatchedAtElapsedRealtimeMs;
        String graceCleanupExecutorRejectErrorClass = connection.graceCleanupExecutorRejectErrorClass;
        synchronized (connection.graceLock) {
            if (connection.graceRunnable != null) mainHandler.removeCallbacks(connection.graceRunnable);
            connection.graceRunnable = null;
            connection.graceDeadlineEpochMs = null;
            connection.graceScheduledAtEpochMs = null;
            connection.graceScheduledAtElapsedRealtimeMs = null;
            connection.graceDeadlineElapsedRealtimeMs = null;
        }
        connection.state = "closed";
        closeChildren(connection);
        connections.remove(connection.connectionId, connection);
        ClientCloseResult closeResult = closeClient(connection.client);
        long transportCloseCompletedAtEpochMs = System.currentTimeMillis();
        long transportCloseCompletedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
        if (notify) {
            JSObject event = new JSObject()
                .put("connectionId", connection.connectionId)
                .put("generationId", connection.generationId)
                .put("state", "closed")
                .put("reason", reason);
            if ("grace-expired".equals(reason)) {
                event.put("nativeClosedAtEpochMs", closedAtEpochMs)
                    .put("nativeClosedAtElapsedRealtimeMs", closedAtElapsedRealtimeMs)
                    .put("nativeTransportCloseCompletedAtEpochMs", transportCloseCompletedAtEpochMs)
                    .put("nativeTransportCloseCompletedAtElapsedRealtimeMs", transportCloseCompletedAtElapsedRealtimeMs)
                    .put("nativeSocketClosedAfterClose", closeResult.socketClosed)
                    .put("nativeClientSocketDetachedAfterClose", closeResult.clientSocketDetached)
                    .put("nativeClientReportedConnectedAfterClose", closeResult.clientReportedConnected)
                    .put("nativeSshjDisconnectErrorClass", closeResult.disconnectErrorClass)
                    .put("nativeSshjClientCloseErrorClass", closeResult.clientCloseErrorClass)
                    .put("nativeRawSocketCloseFallbackUsed", closeResult.rawSocketCloseFallbackUsed)
                    .put("nativeRawSocketCloseErrorClass", closeResult.rawSocketCloseErrorClass)
                    .put("nativeCleanupExecutorRejectErrorClass", graceCleanupExecutorRejectErrorClass);
                if (graceExpiryDispatchedAtEpochMs != null) {
                    event.put("nativeGraceExpiryDispatchedAtEpochMs", graceExpiryDispatchedAtEpochMs);
                }
                if (graceExpiryDispatchedAtElapsedRealtimeMs != null) {
                    event.put("nativeGraceExpiryDispatchedAtElapsedRealtimeMs", graceExpiryDispatchedAtElapsedRealtimeMs);
                }
                if (graceScheduledAtEpochMs != null) event.put("nativeGraceScheduledAtEpochMs", graceScheduledAtEpochMs);
                if (graceScheduledAtElapsedRealtimeMs != null) {
                    event.put("nativeGraceScheduledAtElapsedRealtimeMs", graceScheduledAtElapsedRealtimeMs);
                }
                if (graceDeadlineEpochMs != null) event.put("nativeGraceDeadlineEpochMs", graceDeadlineEpochMs);
                if (graceDeadlineElapsedRealtimeMs != null) {
                    event.put("nativeGraceDeadlineElapsedRealtimeMs", graceDeadlineElapsedRealtimeMs);
                }
            }
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

    private static ClientCloseResult closeClient(SSHClient client) {
        Socket socket = client.getSocket();
        try {
            client.setTimeout(1000);
        } catch (Exception ignored) {}
        ClientCloseAttempt closeAttempt = closeSshjTransportAndSocket(socket, client::disconnect, client::close);
        boolean clientSocketDetached = client.getSocket() == null;
        boolean clientReportedConnected = client.isConnected();
        return new ClientCloseResult(closeAttempt, clientSocketDetached, clientReportedConnected);
    }

    static ClientCloseAttempt closeSshjTransportAndSocket(Socket socket, CloseOperation disconnect, CloseOperation close) {
        String disconnectErrorClass = "";
        String clientCloseErrorClass = "";
        String rawSocketCloseErrorClass = "";
        boolean rawSocketCloseFallbackUsed = false;
        try {
            disconnect.run();
        } catch (Exception ignored) {
            disconnectErrorClass = ignored.getClass().getSimpleName();
        }
        try {
            close.run();
        } catch (Exception ignored) {
            clientCloseErrorClass = ignored.getClass().getSimpleName();
        } finally {
            if (socket != null && !socket.isClosed()) {
                rawSocketCloseFallbackUsed = true;
                try {
                    socket.close();
                } catch (Exception ignored) {
                    rawSocketCloseErrorClass = ignored.getClass().getSimpleName();
                }
            }
        }
        boolean socketClosed = socket != null && socket.isClosed();
        return new ClientCloseAttempt(
            socketClosed,
            disconnectErrorClass,
            clientCloseErrorClass,
            rawSocketCloseFallbackUsed,
            rawSocketCloseErrorClass
        );
    }

    @FunctionalInterface
    interface CloseOperation {
        void run() throws Exception;
    }

    static final class ClientCloseAttempt {
        final boolean socketClosed;
        final String disconnectErrorClass;
        final String clientCloseErrorClass;
        final boolean rawSocketCloseFallbackUsed;
        final String rawSocketCloseErrorClass;

        ClientCloseAttempt(
            boolean socketClosed,
            String disconnectErrorClass,
            String clientCloseErrorClass,
            boolean rawSocketCloseFallbackUsed,
            String rawSocketCloseErrorClass
        ) {
            this.socketClosed = socketClosed;
            this.disconnectErrorClass = disconnectErrorClass;
            this.clientCloseErrorClass = clientCloseErrorClass;
            this.rawSocketCloseFallbackUsed = rawSocketCloseFallbackUsed;
            this.rawSocketCloseErrorClass = rawSocketCloseErrorClass;
        }
    }

    private static final class ClientCloseResult {
        final boolean socketClosed;
        final boolean clientSocketDetached;
        final boolean clientReportedConnected;
        final String disconnectErrorClass;
        final String clientCloseErrorClass;
        final boolean rawSocketCloseFallbackUsed;
        final String rawSocketCloseErrorClass;

        ClientCloseResult(ClientCloseAttempt attempt, boolean clientSocketDetached, boolean clientReportedConnected) {
            this.socketClosed = attempt.socketClosed;
            this.clientSocketDetached = clientSocketDetached;
            this.clientReportedConnected = clientReportedConnected;
            this.disconnectErrorClass = attempt.disconnectErrorClass;
            this.clientCloseErrorClass = attempt.clientCloseErrorClass;
            this.rawSocketCloseFallbackUsed = attempt.rawSocketCloseFallbackUsed;
            this.rawSocketCloseErrorClass = attempt.rawSocketCloseErrorClass;
        }
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

    private static final class ResolvedSftpPath {
        final String path;
        final FileAttributes attributes;
        final boolean exists;

        ResolvedSftpPath(String path, FileAttributes attributes, boolean exists) {
            this.path = path;
            this.attributes = attributes;
            this.exists = exists;
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

    private static final class HostKeyPinExpectation {
        final String keyType;
        final String keyB64;
        final String fingerprintSha256;

        HostKeyPinExpectation(String keyType, String keyB64, String fingerprintSha256) {
            this.keyType = keyType;
            this.keyB64 = keyB64;
            this.fingerprintSha256 = fingerprintSha256;
        }

        boolean matches(PresentedHostKey presented) {
            if (presented == null) return false;
            if (fingerprintSha256 != null) return fingerprintSha256.equals(presented.fingerprintSha256);
            return keyType != null
                && keyB64 != null
                && keyType.equals(presented.keyType)
                && keyB64.equals(presented.keyB64);
        }
    }

    private static final class ConnectAttempt {
        final String requestId;
        final SSHClient client;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean clientClosed = new AtomicBoolean(false);

        ConnectAttempt(String requestId, SSHClient client) {
            this.requestId = requestId;
            this.client = client;
        }

        void closeClient() {
            if (clientClosed.compareAndSet(false, true)) SshCapabilityPlugin.closeClient(client);
        }
    }

    private static final class PinVerifier implements HostKeyVerifier {
        private final HostKeyPinExpectation expectedHostKey;
        private final PresentedHostKey presented;
        private final ConnectAttempt attempt;

        PinVerifier(HostKeyPinExpectation expectedHostKey, PresentedHostKey presented, ConnectAttempt attempt) {
            this.expectedHostKey = expectedHostKey;
            this.presented = presented;
            this.attempt = attempt;
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            if (attempt.cancelled.get()) return false;
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
                presented.trusted = expectedHostKey != null && expectedHostKey.matches(presented);
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
        final String connectRequestId;
        final SSHClient client;
        final Semaphore channelPermits = new Semaphore(MAX_CHANNELS_PER_CONNECTION);
        final AtomicBoolean intentionalClose = new AtomicBoolean(false);
        final Object sftpLock = new Object();
        final Object graceLock = new Object();
        volatile String state = "connected";
        volatile SFTPClient sftpClient;
        volatile Runnable graceRunnable;
        volatile Long graceDeadlineEpochMs;
        volatile Long graceScheduledAtEpochMs;
        volatile Long graceScheduledAtElapsedRealtimeMs;
        volatile Long graceDeadlineElapsedRealtimeMs;
        volatile Long graceExpiryDispatchedAtEpochMs;
        volatile Long graceExpiryDispatchedAtElapsedRealtimeMs;
        volatile String graceCleanupExecutorRejectErrorClass = "";

        SshConnection(String connectionId, String generationId, String hostId, String connectRequestId, SSHClient client) {
            this.connectionId = connectionId;
            this.generationId = generationId;
            this.hostId = hostId;
            this.connectRequestId = connectRequestId;
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
