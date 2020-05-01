package org.netpreserve.pagedrover;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class StorageTest {
    @Test
    public void testCopyHeader() throws IOException {
        String data = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nshould not be read";
        byte[] copied = Storage.readHeaderOnly(Channels.newChannel(new ByteArrayInputStream(data.getBytes(StandardCharsets.US_ASCII))));
        assertEquals("HTTP/1.1 200 OK\r\n" +
                "Content-Length: 5\r\n" +
                "\r\n", new String(copied, StandardCharsets.US_ASCII));
    }
}