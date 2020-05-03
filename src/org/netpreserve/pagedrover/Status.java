package org.netpreserve.pagedrover;

public class Status {
    public static final int NOT_MODIFIED = 304;
    public static final int CONNECT_FAILED = -2;
    public static final int ROBOTS_DISALLOWED = -9998;

    public static boolean isSuccess(int status) {
        return status >= 200 && status <= 299;
    }
}
