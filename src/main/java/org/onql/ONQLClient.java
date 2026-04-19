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
 * A thread-safe, synchronous Java client for the ONQL TCP server.
 *
 * <p>Protocol wire format:
 * <ul>
 *   <li>Request:  {@code {rid}\x1E{keyword}\x1E{payload}\x04}</li>
 *   <li>Response: {@code {rid}\x1E{source}\x1E{payload}\x04}</li>
 * </ul>
 *
 * <p>A background reader thread continuously reads from the socket, parses
 * incoming messages, and dispatches them to the appropriate pending future
 * or subscription callback.
 */
public final class ONQLClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ONQLClient.class.getName());

    private static final byte EOM = 0x04;          // end-of-message
    private static final String DELIMITER = "\u001E"; // record separator
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Thread readerThread;
    private volatile boolean closed = false;

    private final ConcurrentHashMap<String, CompletableFuture<ONQLResponse>> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SubscriptionCallback> subscriptions = new ConcurrentHashMap<>();

    /** Default database name used by insert/update/delete/onql. */
    private volatile String db = "";

    // ------------------------------------------------------------------ //
    //  Subscription callback
    // ------------------------------------------------------------------ //

    /**
     * Callback interface for streaming subscription messages.
     */
    @FunctionalInterface
    public interface SubscriptionCallback {
        /**
         * Called on the reader thread whenever a new frame arrives for this
         * subscription.
         *
         * @param rid     the subscription request ID
         * @param source  the source field from the server response
         * @param payload the payload body
         */
        void onMessage(String rid, String source, String payload);
    }

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
     *
     * @param host server hostname
     * @param port server port
     * @return a connected client instance
     * @throws IOException if the TCP connection cannot be established
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

    /**
     * Send a request and block until the response arrives or {@code timeoutMs}
     * elapses.
     *
     * @param keyword   the ONQL keyword / command
     * @param payload   the request payload (often JSON)
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the parsed server response
     * @throws TimeoutException if no response is received within the timeout
     * @throws IOException      if the connection is closed or broken
     */
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

    /**
     * Open a streaming subscription. All subsequent frames that arrive with
     * the returned request ID will be delivered to {@code callback}.
     *
     * @param onquery ONQL onquery string (may be empty)
     * @param query   ONQL query expression
     * @param callback handler invoked for each streamed message
     * @return the subscription request ID (pass to {@link #unsubscribe} to stop)
     * @throws IOException if the frame cannot be sent
     */
    public String subscribe(String onquery, String query, SubscriptionCallback callback) throws IOException {
        if (closed) {
            throw new IOException("Client is not connected.");
        }

        String rid = generateRequestId();
        subscriptions.put(rid, callback);

        String jsonPayload = "{\"onquery\":" + jsonString(onquery) + ",\"query\":" + jsonString(query) + "}";
        sendFrame(rid, "subscribe", jsonPayload);
        return rid;
    }

    /**
     * Stop receiving events for a subscription. Removes the local callback
     * and sends an {@code unsubscribe} frame to the server.
     *
     * @param rid the subscription request ID returned by {@link #subscribe}
     */
    public void unsubscribe(String rid) {
        subscriptions.remove(rid);

        if (closed) {
            return;
        }

        try {
            String jsonPayload = "{\"rid\":" + jsonString(rid) + "}";
            sendFrame(rid, "unsubscribe", jsonPayload);
        } catch (IOException e) {
            LOG.log(Level.FINE, "unsubscribe frame send failed (ignored)", e);
        }
    }

    /**
     * Close the connection and release all resources. Any pending futures
     * will complete exceptionally with an {@link IOException}.
     */
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
        subscriptions.clear();

        LOG.info("Connection closed.");
    }

    // ------------------------------------------------------------------ //
    //  Internals
    // ------------------------------------------------------------------ //

    /**
     * Background reader loop. Reads bytes from the socket until an EOM byte
     * is encountered, parses the message, and dispatches it.
     */
    private void readerLoop() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);

        try {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                int b = in.read();
                if (b == -1) {
                    // server closed connection
                    LOG.warning("Connection closed by server.");
                    break;
                }

                if (b == EOM) {
                    // full message received
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

        // Fail any outstanding requests
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

        // Check subscriptions first
        SubscriptionCallback cb = subscriptions.get(rid);
        if (cb != null) {
            try {
                cb.onMessage(rid, source, payload);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error in subscription callback", e);
            }
            return;
        }

        // Resolve pending request future
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

    /**
     * Generate a random 8-character lowercase hex string for use as a request ID.
     */
    private static String generateRequestId() {
        int value = ThreadLocalRandom.current().nextInt();
        return String.format("%08x", value);
    }

    // ------------------------------------------------------------------ //
    //  Direct ORM-style API (insert / update / delete / onql / build)
    // ------------------------------------------------------------------ //

    /**
     * Set the default database name used by {@link #insert}, {@link #update},
     * {@link #delete}, and {@link #onql}. Returns {@code this} so calls can be
     * chained.
     *
     * @param db database name
     * @return this client
     */
    public ONQLClient setup(String db) {
        this.db = db == null ? "" : db;
        return this;
    }

    /**
     * Parse the standard {@code {"error": "...", "data": "..."}} server envelope.
     * Returns the raw {@code data} substring (as sent by the server) on success.
     * Throws a {@link RuntimeException} if the server returned a non-empty
     * {@code error} field.
     *
     * <p>The data is returned verbatim — decode it with your preferred JSON
     * library.</p>
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
            // Strip surrounding quotes if it's a JSON string.
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
     * Insert one or more records into {@code table}. The caller is responsible
     * for JSON-serializing the record(s) into {@code recordsJson}.
     *
     * @param table        target table name
     * @param recordsJson  a JSON-serialized record object, or array of records
     * @return the {@code data} substring of the server envelope
     */
    public String insert(String table, String recordsJson) throws Exception {
        String payload = "{"
                + "\"db\":"      + jsonString(db)    + ","
                + "\"table\":"   + jsonString(table) + ","
                + "\"records\":" + recordsJson
                + "}";
        ONQLResponse res = sendRequest("insert", payload);
        return processResult(res.getPayload());
    }

    /**
     * Update records in {@code table} matching {@code queryJson}. Uses
     * {@code "default"} proto-pass and no explicit IDs.
     */
    public String update(String table, String recordsJson, String queryJson) throws Exception {
        return update(table, recordsJson, queryJson, "default", "[]");
    }

    /**
     * Update records in {@code table}.
     *
     * @param table        target table
     * @param recordsJson  JSON object with fields to update
     * @param queryJson    JSON query to match
     * @param protopass    proto-pass profile (e.g. {@code "default"})
     * @param idsJson      JSON array of explicit record IDs (e.g. {@code "[]"})
     */
    public String update(String table, String recordsJson, String queryJson,
                         String protopass, String idsJson) throws Exception {
        String payload = "{"
                + "\"db\":"        + jsonString(db)        + ","
                + "\"table\":"     + jsonString(table)     + ","
                + "\"records\":"   + recordsJson           + ","
                + "\"query\":"     + queryJson             + ","
                + "\"protopass\":" + jsonString(protopass) + ","
                + "\"ids\":"       + idsJson
                + "}";
        ONQLResponse res = sendRequest("update", payload);
        return processResult(res.getPayload());
    }

    /**
     * Delete records from {@code table} matching {@code queryJson}. Uses
     * {@code "default"} proto-pass and no explicit IDs.
     */
    public String delete(String table, String queryJson) throws Exception {
        return delete(table, queryJson, "default", "[]");
    }

    /**
     * Delete records from {@code table}.
     *
     * @param table      target table
     * @param queryJson  JSON query to match
     * @param protopass  proto-pass profile
     * @param idsJson    JSON array of explicit record IDs
     */
    public String delete(String table, String queryJson,
                         String protopass, String idsJson) throws Exception {
        String payload = "{"
                + "\"db\":"        + jsonString(db)        + ","
                + "\"table\":"     + jsonString(table)     + ","
                + "\"query\":"     + queryJson             + ","
                + "\"protopass\":" + jsonString(protopass) + ","
                + "\"ids\":"       + idsJson
                + "}";
        ONQLResponse res = sendRequest("delete", payload);
        return processResult(res.getPayload());
    }

    /**
     * Execute a raw ONQL query with defaults
     * ({@code protopass="default"}, empty context).
     */
    public String onql(String query) throws Exception {
        return onql(query, "default", "", "[]");
    }

    /**
     * Execute a raw ONQL query.
     *
     * @param query          ONQL query text
     * @param protopass      proto-pass profile
     * @param ctxkey         context key
     * @param ctxvaluesJson  JSON array of context values (e.g. {@code "[]"})
     * @return the decoded {@code data} substring of the server envelope
     */
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
     * Replace {@code $1, $2, ...} placeholders in {@code query} with values.
     * Strings are double-quoted, numbers and booleans are inlined verbatim.
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
     * Extract a single JSON value (string / number / boolean / null / object /
     * array) starting at {@code start} in {@code raw}, skipping leading spaces.
     * Returns the raw substring, preserving structure.
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

    /**
     * Minimal JSON string escaping (no external dependencies).
     */
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
