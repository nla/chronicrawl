package org.netpreserve.chronicrawl;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ExternalArchiveTest {
    private static TestServer testServer;

    @BeforeClass
    public static void startServer() throws IOException {
        testServer = new TestServer();
    }

    @AfterClass
    public static void stopServer() {
        testServer.stop();
    }

    @Test
    public void test() throws IOException {
        ExternalArchive external = new ExternalArchive(testServer.url() + "/cdx");
        List<Visit> visits = external.list("http://example.org/");
        assertEquals(5, visits.size());
    }

}