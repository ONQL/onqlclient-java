package org.onql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A thread-safe Java client for the ONQL TCP server.
 *
 * <p>Protocol wire format:
 * <ul>
 *   <li>Request:  {@code {rid}\x1E{keyword}\x1E{payload}\x04}</li>
 *   <li>Response: {@code {rid}\x1E{source}\x1E{payload}\x04}</li>
 * </ul>
 */
public final class ONQLClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ONQLClient.class.getName());

    private static final byte EOM = 0x04;
    private static final String DELIMITER = "\u001E";
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Thread readerThread;
    private volatile boolean closed = false;

    private final ConcurrentHashMap<String, CompletableFuture<ONQLResponse>> pendingRequests = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ //
    //  Construction
    // ------------------------------------------------------------------ //

    private ONQLClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();

        this.readerThread = new Thread(this::readerLoop, "onql-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /**
     * Create and return a connected {@link ONQLClient}.
     */
    public static ONQLClient create(String host, int port) throws IOException {
        Socket sock = new Socket(host, port);
        sock.setKeepAlive(true);
        LOG.info("Connected to ONQL server at " + host + ":" + port);
        return new ONQLClient(sock);
    }

    // ------------------------------------------------------------------ //
    //  Public API
    // ------------------------------------------------------------------ //

    /**
     * Send a request and block until the response arrives (default 10 s timeout).
     */
    public ONQLResponse sendRequest(String keyword, String payload) throws Exception {
        return sendRequest(keyword, payload, DEFAULT_TIMEOUT_MS);
    }

    public ONQLResponse sendRequest(String keyword, String payload, long timeoutMs) throws Exception {
        if (closed) {
            throw new IOException("Client is not connected.");
        }

        String rid = generateRequestId();
        CompletableFuture<ONQLResponse> future = new CompletableFuture<>();
        pendingRequests.put(rid, future);

        try {
            sendFrame(rid, keyword, payload);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            LOG.warning("Request " + rid + " timed out after " + timeoutMs + " ms");
            throw te;
        } finally {
            pendingRequests.remove(rid);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Error closing socket", e);
        }

        readerThread.interrupt();

        IOException lost = new IOException("Connection closed.");
        for (CompletableFuture<ONQLResponse> f : pendingRequests.values()) {
            f.completeExceptionally(lost);
        }
        pendingRequests.clear();

        LOG.info("Connection closed.");
    }

    // ------------------------------------------------------------------ //
    //  Internals
    // ------------------------------------------------------------------ //

    private void readerLoop() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);

        try {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                int b = in.read();
                if (b == -1) {
                    LOG.warning("Connection closed by server.");
                    break;
                }

                if (b == EOM) {
                    String message = buffer.toString(StandardCharsets.UTF_8.name());
                    buffer.reset();
                    handleMessage(message);
                } else {
                    buffer.write(b);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.log(Level.WARNING, "Reader loop error", e);
            }
        }

        if (!closed) {
            closed = true;
            IOException lost = new IOException("Connection lost.");
            for (CompletableFuture<ONQLResponse> f : pendingRequests.values()) {
                f.completeExceptionally(lost);
            }
        }
    }

    private void handleMessage(String message) {
        String[] parts = message.split(DELIMITER, 3);
        if (parts.length != 3) {
            LOG.warning("Received malformed response: " + message);
            return;
        }

        String rid = parts[0];
        String source = parts[1];
        String payload = parts[2];

        CompletableFuture<ONQLResponse> future = pendingRequests.get(rid);
        if (future != null) {
            future.complete(new ONQLResponse(rid, source, payload));
        } else {
            LOG.fine("Received response for unknown request ID: " + rid);
        }
    }

    /**
     * Write a framed message to the socket.
     * Format: {@code {rid}\x1E{keyword}\x1E{payload}\x04}
     */
    private synchronized void sendFrame(String rid, String keyword, String payload) throws IOException {
        String frame = rid + DELIMITER + keyword + DELIMITER + payload;
        byte[] data = frame.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[data.length + 1];
        System.arraycopy(data, 0, packet, 0, data.length);
        packet[data.length] = EOM;
        out.write(packet);
        out.flush();
    }

    private static String generateRequestId() {
        int value = ThreadLocalRandom.current().nextInt();
        return String.format("%08x", value);
    }

    // ------------------------------------------------------------------ //
    //  Direct ORM-style API (insert / update / delete / onql / build)
    //
    //  `path` is a dotted string:
    //    "mydb.users"      -> whole `users` table in database `mydb`
    //    "mydb.users.u1"   -> record with id `u1`
    // ------------------------------------------------------------------ //

    private static final class Path {
        final String db;
        final String table;
        final String id;
        Path(String db, String table, String id) {
            this.db = db; this.table = table; this.id = id;
        }
    }

    private static Path parsePath(String path, boolean requireId) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException(
                "Path must be a non-empty string like \"db.table\" or \"db.table.id\"");
        }
        int dot1 = path.indexOf('.');
        if (dot1 <= 0 || dot1 == path.length() - 1) {
            throw new IllegalArgumentException(
                "Path \"" + path + "\" must contain at least \"db.table\"");
        }
        int dot2 = path.indexOf('.', dot1 + 1);
        String db = path.substring(0, dot1);
        String table, id;
        if (dot2 == -1) {
            table = path.substring(dot1 + 1);
            id = "";
        } else {
            table = path.substring(dot1 + 1, dot2);
            id = path.substring(dot2 + 1);
        }
        if (requireId && id.isEmpty()) {
            throw new IllegalArgumentException(
                "Path \"" + path + "\" must include a record id: \"db.table.id\"");
        }
        return new Path(db, table, id);
    }

    /**
     * Parse the standard {@code {"error": "...", "data": ...}} server envelope.
     * Returns the raw {@code data} substring on success; throws
     * {@link RuntimeException} if the {@code error} field is non-empty.
     */
    public static String processResult(String raw) {
        if (raw == null) {
            throw new RuntimeException("null response");
        }
        String errKey = "\"error\"";
        int eIdx = raw.indexOf(errKey);
        String errorValue = "";
        if (eIdx >= 0) {
            int colon = raw.indexOf(':', eIdx + errKey.length());
            if (colon >= 0) {
                errorValue = extractJsonValue(raw, colon + 1);
            }
        }
        if (!errorValue.isEmpty() && !"null".equals(errorValue)
                && !"\"\"".equals(errorValue) && !"false".equals(errorValue)) {
            String msg = errorValue;
            if (msg.length() >= 2 && msg.charAt(0) == '"' && msg.charAt(msg.length() - 1) == '"') {
                msg = msg.substring(1, msg.length() - 1);
            }
            throw new RuntimeException(msg);
        }
        String dataKey = "\"data\"";
        int dIdx = raw.indexOf(dataKey);
        if (dIdx < 0) {
            return "";
        }
        int colon = raw.indexOf(':', dIdx + dataKey.length());
        return colon >= 0 ? extractJsonValue(raw, colon + 1) : "";
    }

    /**
     * Insert a single record at {@code path} (e.g. {@code "mydb.users"}).
     * The caller JSON-serializes the record into {@code recordJson}.
     */
    public String insert(String path, String recordJson) throws Exception {
        Path p = parsePath(path, false);
        String payload = "{"
                + "\"db\":"      + jsonString(p.db)    + ","
                + "\"table\":"   + jsonString(p.table) + ","
                + "\"records\":" + recordJson
                + "}";
        ONQLResponse res = sendRequest("insert", payload);
        return processResult(res.getPayload());
    }

    /**
     * Update the record at {@code path} (e.g. {@code "mydb.users.u1"}).
     */
    public String update(String path, String recordJson) throws Exception {
        return update(path, recordJson, "default");
    }

    public String update(String path, String recordJson, String protopass) throws Exception {
        Path p = parsePath(path, true);
        String idsJson = "[" + jsonString(p.id) + "]";
        String payload = "{"
                + "\"db\":"        + jsonString(p.db)       + ","
                + "\"table\":"     + jsonString(p.table)    + ","
                + "\"records\":"   + recordJson             + ","
                + "\"query\":\"\","
                + "\"protopass\":" + jsonString(protopass)  + ","
                + "\"ids\":"       + idsJson
                + "}";
        ONQLResponse res = sendRequest("update", payload);
        return processResult(res.getPayload());
    }

    /**
     * Delete the record at {@code path} (e.g. {@code "mydb.users.u1"}).
     */
    public String delete(String path) throws Exception {
        return delete(path, "default");
    }

    public String delete(String path, String protopass) throws Exception {
        Path p = parsePath(path, true);
        String idsJson = "[" + jsonString(p.id) + "]";
        String payload = "{"
                + "\"db\":"        + jsonString(p.db)       + ","
                + "\"table\":"     + jsonString(p.table)    + ","
                + "\"query\":\"\","
                + "\"protopass\":" + jsonString(protopass)  + ","
                + "\"ids\":"       + idsJson
                + "}";
        ONQLResponse res = sendRequest("delete", payload);
        return processResult(res.getPayload());
    }

    public String onql(String query) throws Exception {
        return onql(query, "default", "", "[]");
    }

    public String onql(String query, String protopass, String ctxkey, String ctxvaluesJson) throws Exception {
        String payload = "{"
                + "\"query\":"     + jsonString(query)     + ","
                + "\"protopass\":" + jsonString(protopass) + ","
                + "\"ctxkey\":"    + jsonString(ctxkey)    + ","
                + "\"ctxvalues\":" + ctxvaluesJson
                + "}";
        ONQLResponse res = sendRequest("onql", payload);
        return processResult(res.getPayload());
    }

    /**
     * Replace {@code $1}, {@code $2}, ... placeholders in {@code query} with values.
     * Strings are double-quoted; numbers and booleans are inlined verbatim.
     */
    public String build(String query, Object... values) {
        for (int i = 0; i < values.length; i++) {
            String placeholder = "$" + (i + 1);
            Object value = values[i];
            String replacement;
            if (value instanceof String) {
                replacement = "\"" + value + "\"";
            } else if (value instanceof Boolean) {
                replacement = value.toString();
            } else if (value instanceof Number) {
                replacement = value.toString();
            } else {
                replacement = String.valueOf(value);
            }
            query = query.replace(placeholder, replacement);
        }
        return query;
    }

    /**
     * Extract a single JSON value starting at {@code start} in {@code raw}.
     */
    private static String extractJsonValue(String raw, int start) {
        int i = start;
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
        if (i >= raw.length()) return "";
        char c = raw.charAt(i);
        int end;
        if (c == '"') {
            end = i + 1;
            while (end < raw.length()) {
                char ch = raw.charAt(end);
                if (ch == '\\') { end += 2; continue; }
                if (ch == '"')  { end++; break; }
                end++;
            }
        } else if (c == '{' || c == '[') {
            char open = c;
            char close = (c == '{') ? '}' : ']';
            int depth = 1;
            end = i + 1;
            while (end < raw.length() && depth > 0) {
                char ch = raw.charAt(end);
                if (ch == '"') {
                    end++;
                    while (end < raw.length()) {
                        char s = raw.charAt(end);
                        if (s == '\\') { end += 2; continue; }
                        if (s == '"')  { end++; break; }
                        end++;
                    }
                    continue;
                }
                if (ch == open)  depth++;
                if (ch == close) depth--;
                end++;
            }
        } else {
            end = i;
            while (end < raw.length()) {
                char ch = raw.charAt(end);
                if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) break;
                end++;
            }
        }
        return raw.substring(i, Math.min(end, raw.length()));
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
