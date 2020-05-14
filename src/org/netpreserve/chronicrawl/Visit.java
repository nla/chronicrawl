package org.netpreserve.chronicrawl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public class Visit {
    public final long originId;
    public final long pathId;
    public final Instant date;
    public final String method;
    public final int status;
    public final String contentType;
    public final long contentLength;
    public final UUID warcId;
    public final long requestPosition;
    public final long requestLength;
    public final byte[] requestPayloadDigest;
    public final long responsePosition;
    public final long responseLength;
    public final byte[] responsePayloadDigest;

    public Visit(ResultSet rs) throws SQLException {
        originId = rs.getLong("origin_id");
        pathId = rs.getLong("path_id");
        date = Instant.ofEpochMilli(rs.getLong("date"));
        method = rs.getString("method");
        status = rs.getInt("status");
        contentType = rs.getString("content_type");
        contentLength = rs.getLong("content_length");
        warcId = Database.getUUID(rs, "warc_id");
        requestPosition = rs.getLong("request_position");
        requestLength = rs.getLong("request_length");
        requestPayloadDigest = rs.getBytes("request_payload_digest");
        responsePosition = rs.getLong("response_position");
        responseLength = rs.getLong("response_length");
        responsePayloadDigest = rs.getBytes("response_payload_digest");
    }

    public String href() {
        return "visit?o=" + originId + "&p=" + pathId + "&d=" + date.toEpochMilli();
    }
}
