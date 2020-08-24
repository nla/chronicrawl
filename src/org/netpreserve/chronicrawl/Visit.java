package org.netpreserve.chronicrawl;

import org.apache.commons.codec.binary.Hex;

import java.time.Instant;
import java.util.UUID;

public class Visit {
    public final long originId;
    public final long pathId;
    public final Instant date;
    public final String method;
    public final int status;
    public final String contentType;
    public final Long contentLength;
    public final UUID warcId;
    public final long requestPosition;
    public final long requestLength;
    public final byte[] requestPayloadDigest;
    public final long responsePosition;
    public final long responseLength;
    public final byte[] responsePayloadDigest;
    public final Instant revisitOfDate;

    public Visit(long originId, long pathId, Instant date, String method, int status, String contentType, Long contentLength, UUID warcId, long requestPosition, long requestLength, byte[] requestPayloadDigest, long responsePosition, long responseLength, byte[] responsePayloadDigest, Instant revisitOfDate) {
        this.originId = originId;
        this.pathId = pathId;
        this.date = date;
        this.method = method;
        this.status = status;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.warcId = warcId;
        this.requestPosition = requestPosition;
        this.requestLength = requestLength;
        this.requestPayloadDigest = requestPayloadDigest;
        this.responsePosition = responsePosition;
        this.responseLength = responseLength;
        this.responsePayloadDigest = responsePayloadDigest;
        this.revisitOfDate = revisitOfDate;
    }

    public String href() {
        return "visit?o=" + originId + "&p=" + pathId + "&d=" + date.toEpochMilli();
    }

    public String digestPreview() {
        if (responsePayloadDigest == null) return "";
        return new Hex().encodeHexString(responsePayloadDigest).substring(0, 8);
    }
}
