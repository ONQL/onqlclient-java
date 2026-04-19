package org.onql;

/**
 * Represents a parsed response from the ONQL server.
 *
 * <p>Wire format: {@code {request_id}\x1E{source}\x1E{payload}\x04}
 */
public final class ONQLResponse {

    private final String requestId;
    private final String source;
    private final String payload;

    public ONQLResponse(String requestId, String source, String payload) {
        this.requestId = requestId;
        this.source = source;
        this.payload = payload;
    }

    /** The 8-char hex request ID that correlates this response to a request. */
    public String getRequestId() {
        return requestId;
    }

    /** The source identifier returned by the server. */
    public String getSource() {
        return source;
    }

    /** The payload body returned by the server. */
    public String getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "ONQLResponse{requestId='" + requestId + "', source='" + source + "', payload='" + payload + "'}";
    }
}
