package org.netpreserve.pagedrover.browser;

public class BrowserException extends RuntimeException {
    public BrowserException(String message) {
        super(message);
    }

    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
}
