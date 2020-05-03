package org.netpreserve.pagedrover;

public class BrowserException extends RuntimeException {
    public BrowserException(String message) {
        super(message);
    }

    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
}
