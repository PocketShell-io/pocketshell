package com.pocketshell.app;

import com.getcapacitor.JSObject;
import java.util.HashMap;
import java.util.Map;

/**
 * The one table that says how each {@code SshCapability} plugin method is
 * scheduled (#3141). A method maps to a lane (calls sharing a lane run in
 * order, see {@link SshCallDispatcher}) keyed by one id field of its payload,
 * or to no lane (unordered). {@code SshCallRoutingTest} asserts by reflection
 * that every {@code @PluginMethod} has exactly one entry, so a new method
 * cannot silently skip routing and a lane typo shows up in one place.
 */
final class SshCallRouting {
    private static final class Route {
        final String kind;
        final String idField;
        Route(String kind, String idField) { this.kind = kind; this.idField = idField; }
    }

    private static final Map<String, Route> ROUTES = new HashMap<>();
    private static final Route UNORDERED = new Route(null, null);

    static {
        // Free: independent, never queue behind anything (cancelOperation must preempt a running connect).
        for (String method : new String[] {"transportCapabilities", "connect", "cancelOperation", "exec", "openPty", "resourceSnapshot"}) {
            ROUTES.put(method, UNORDERED);
        }
        // One connection's lifecycle calls.
        for (String method : new String[] {"getConnectionState", "closeConnection", "scheduleClose", "cancelScheduledClose"}) {
            ROUTES.put(method, new Route("conn", "connectionId"));
        }
        // One PTY: input/size/close in order; reads on their own lane so a <=500 ms wait never blocks a write.
        ROUTES.put("readPty", new Route("pty-read", "channelId"));
        for (String method : new String[] {"writePty", "resizePty", "closePty"}) ROUTES.put(method, new Route("pty-op", "channelId"));
        // One connection's SFTP work (it serializes on sftpLock anyway).
        for (String method : new String[] {"sftpRealPath", "sftpStat", "sftpList", "sftpRead", "sftpWrite", "sftpWriteChunk", "sftpWriteIfUnchanged", "sftpMkdir", "sftpRename", "sftpDelete"}) {
            ROUTES.put(method, new Route("sftp", "connectionId"));
        }
        for (String method : new String[] {"openPortForward", "closePortForward"}) ROUTES.put(method, new Route("fwd", "connectionId"));
    }

    private SshCallRouting() {}

    static boolean isRouted(String method) { return ROUTES.containsKey(method); }

    static boolean isConnect(String method) { return "connect".equals(method); }

    /** The lane for this call, or null when it is unordered. Fails loudly for an unrouted method. */
    static String lane(String method, JSObject data) {
        Route route = ROUTES.get(method);
        if (route == null) throw new IllegalStateException("SSH plugin method has no routing entry: " + method);
        if (route.kind == null) return null;
        return route.kind + ":" + (data == null ? "" : data.optString(route.idField, ""));
    }
}
