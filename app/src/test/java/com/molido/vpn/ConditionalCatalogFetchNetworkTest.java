package com.molido.vpn;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ConditionalCatalogFetchNetworkTest {
    private AtomicLong time;
    @Before public void reset() {
        ConditionalCatalogFetch.resetForTests();
        time = new AtomicLong(1000L);
        ConditionalCatalogFetch.clock = time::get;
    }
    @After public void clean() { ConditionalCatalogFetch.resetForTests(); }

    private static final class Origin implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger requests = new AtomicInteger();
        volatile int status = 200;
        volatile String body = "catalog", control = "public, max-age=300", retryAfter;
        volatile String validator;
        volatile CountDownLatch replyGate;
        final CountDownLatch arrived = new CountDownLatch(1);
        volatile boolean closed;
        final Thread worker;
        Origin() throws IOException {
            worker = new Thread(() -> {
                while (!closed) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("if-none-match:")) validator = line.substring(line.indexOf(':') + 1).trim();
                        }
                        requests.incrementAndGet();
                        arrived.countDown();
                        if (replyGate != null) try { replyGate.await(3, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        byte[] bytes = status == 304 ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                        String headers = "HTTP/1.1 " + status + " Response\r\nContent-Length: " + bytes.length +
                            "\r\nConnection: close\r\nETag: \"a\"\r\nCache-Control: " + control + "\r\n" +
                            (retryAfter == null ? "" : "Retry-After: " + retryAfter + "\r\n") + "\r\n";
                        OutputStream out = socket.getOutputStream();
                        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
                    } catch (IOException ignored) {}
                }
            }, "catalog-origin-fixture");
            worker.setDaemon(true); worker.start();
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/catalog"; }
        public void close() throws Exception { closed = true; server.close(); worker.join(2000); }
    }

    @Test public void freshCopiesAvoidNetworkAndExpiredCopiesRevalidateWithEtag() throws Exception {
        try (Origin origin = new Origin()) {
            assertEquals("catalog", ConditionalCatalogFetch.get(origin.url()));
            for (int i = 0; i < 100; i++) assertEquals("catalog", ConditionalCatalogFetch.get(origin.url()));
            assertEquals(1, origin.requests.get());
            time.addAndGet(300001L); origin.status = 304;
            assertEquals("catalog", ConditionalCatalogFetch.get(origin.url()));
            assertEquals("\"a\"", origin.validator);
            assertEquals(2, origin.requests.get());
        }
    }

    @Test public void rateLimitHonorsRetryAfterAndRecoversWithoutBusyPolling() throws Exception {
        try (Origin origin = new Origin()) {
            origin.status = 429; origin.retryAfter = "120";
            assertNull(ConditionalCatalogFetch.get(origin.url()));
            origin.status = 200;
            for (int i = 0; i < 100; i++) assertNull(ConditionalCatalogFetch.get(origin.url()));
            assertEquals(1, origin.requests.get());
            time.addAndGet(119999L); assertNull(ConditionalCatalogFetch.get(origin.url()));
            time.incrementAndGet(); assertEquals("catalog", ConditionalCatalogFetch.get(origin.url()));
            assertEquals(2, origin.requests.get());
        }
    }

    @Test public void failedWorkerDoesNotBlockIndependentMirror() throws Exception {
        try (Origin primary = new Origin(); Origin mirror = new Origin()) {
            primary.status = 503;
            for (int i = 0; i < 100; i++) {
                assertNull(ConditionalCatalogFetch.get(primary.url()));
                assertEquals("catalog", ConditionalCatalogFetch.get(mirror.url()));
            }
            assertEquals(1, primary.requests.get()); assertEquals(1, mirror.requests.get());
        }
    }

    @Test public void oversizeIsNotCachedAndServerNoStoreIsHonored() throws Exception {
        try (Origin origin = new Origin()) {
            assertNull(ConditionalCatalogFetch.get(origin.url(), 2));
            assertNull(ConditionalCatalogFetch.get(origin.url(), 2));
            assertEquals(1, origin.requests.get());
            time.addAndGet(300001L); origin.body = "ok"; origin.control = "no-store";
            assertEquals("ok", ConditionalCatalogFetch.get(origin.url(), 2));
            assertEquals("ok", ConditionalCatalogFetch.get(origin.url(), 2));
            assertEquals(3, origin.requests.get());
        }
    }

    @Test public void concurrentColdCallersDoNotMultiplyTheUpstreamRequest() throws Exception {
        try (Origin origin = new Origin()) {
            origin.replyGate = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> first = executor.submit(() -> ConditionalCatalogFetch.get(origin.url()));
                assertTrue(origin.arrived.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 100; i++) assertNull(ConditionalCatalogFetch.get(origin.url()));
                assertEquals(1, origin.requests.get());
                origin.replyGate.countDown();
                assertEquals("catalog", first.get(2, TimeUnit.SECONDS));
            } finally { origin.replyGate.countDown(); executor.shutdownNow(); }
        }
    }
}
