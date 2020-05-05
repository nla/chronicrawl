package org.netpreserve.pagedrover;

import java.time.Instant;
import java.util.UUID;

public class Visit {
    public final UUID id;
    public final String method;
    public final long locationId;
    public final Instant date;
    public final int status;
    public final String contentType;
    public final Long contentLength;

    public Visit(UUID id, String method, long locationId, Instant date, int status, String contentType, Long contentLength) {
        this.id = id;
        this.method = method;
        this.locationId = locationId;
        this.date = date;
        this.status = status;
        this.contentType = contentType;
        this.contentLength = contentLength;
    }
}
