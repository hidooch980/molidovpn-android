package com.molido.vpn;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ShardProbeNetworkTest {
    interface Handler { void run(Socket socket, int attempt) throws Exception; }
    static class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final List<String> hosts = new CopyOnWriteArrayList<>();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final Thread listener;
        Fixture(Handler handler) throws Exception {
            listener = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        sockets.add(socket);
                        socket.setSoTimeout(2000);
                        handler.run(socket, accepted.incrementAndGet());
                    } catch (IOException expectedDisconnect) {
                    } catch (Throwable unexpected) { failure.compareAndSet(null, unexpected); }
                }
            }, "local-socks-fixture");
            listener.setDaemon(true);
            listener.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception {
            server.close();
            for (Socket socket : sockets) socket.close();
            listener.join(2500);
            assertFalse("fixture thread must stop", listener.isAlive());
            if (failure.get() != null) throw new AssertionError("SOCKS fixture failed", failure.get());
        }
    }
    static String socks(Socket socket, boolean ipv6Bind, int version) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        OutputStream output = socket.getOutputStream();
        byte[] hello = new byte[3]; input.readFully(hello);
        assertArrayEquals(new byte[] {5,1,0}, hello);
        output.write(new byte[] {5,0}); output.flush();
        assertEquals(5, input.readUnsignedByte());
        assertEquals(1, input.readUnsignedByte());
        assertEquals(0, input.readUnsignedByte());
        assertEquals("hostname must be resolved by proxy", 3, input.readUnsignedByte());
        byte[] host = new byte[input.readUnsignedByte()]; input.readFully(host);
        assertEquals(80, input.readUnsignedShort());
        output.write(new byte[] {(byte) version,0,0,(byte) (ipv6Bind ? 4 : 1)});
        output.write(new byte[ipv6Bind ? 18 : 6]); output.flush();
        return new String(host, StandardCharsets.US_ASCII);
    }
    static void request(Socket socket) throws Exception {
        InputStream input = socket.getInputStream();
        int tail=0;
        for (int i=0;i<4096;i++) {
            int b=input.read();
            if (b<0) throw new EOFException();
            tail=(tail<<8)|b;
            if (tail==0x0d0a0d0a) return;
        }
        throw new IOException("request too large");
    }
    static void response(Socket socket, String status) throws Exception {
        socket.getOutputStream().write((status+"\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    @Test public void destinationNameGoesInsideSocksInsteadOfCarrierResolution() throws Exception {
        List<String> hosts = new CopyOnWriteArrayList<>();
        try (Fixture f=new Fixture((s,n)->{
            hosts.add(socks(s,false,5)); request(s); response(s,"HTTP/1.1 204 No Content");
        })) {
            assertTrue(ShardProbe.INSTANCE.check(f.port(),1000));
            assertEquals(Collections.singletonList("cp.cloudflare.com"), hosts);
        }
    }
    @Test public void ipv6SocksBoundAddressIsConsumedBeforeHttp() throws Exception {
        try (Fixture f=new Fixture((s,n)->{ socks(s,true,5); request(s); response(s,"HTTP/1.1 204 No Content"); })) {
            assertTrue(ShardProbe.INSTANCE.check(f.port(),1000));
        }
    }
    @Test public void malformedSocksVersionCannotBecomeAHealthyNode() throws Exception {
        try (Fixture f=new Fixture((s,n)->{ socks(s,false,4); })) {
            assertFalse(ShardProbe.INSTANCE.check(f.port(),1000));
            assertEquals(1,f.accepted.get());
        }
    }
    @Test public void status2040AndPortal200AreNotHealthy() throws Exception {
        for (String status : Arrays.asList("HTTP/1.1 2040 Bogus","HTTP/1.1 200 Captive Portal")) {
            try (Fixture f=new Fixture((s,n)->{ socks(s,false,5); request(s); response(s,status); })) {
                assertFalse(ShardProbe.INSTANCE.check(f.port(),1000));
                assertEquals(3,f.accepted.get());
            }
        }
    }
    @Test public void truncatedHttpResponseIsNotHealthy() throws Exception {
        try (Fixture f=new Fixture((s,n)->{
            socks(s,false,5); request(s);
            s.getOutputStream().write("HTTP/1.1 204".getBytes(StandardCharsets.US_ASCII));
        })) { assertFalse(ShardProbe.INSTANCE.check(f.port(),1000)); }
    }
    @Test public void slowTrickleCannotExtendTheWholeProbeDeadline() throws Exception {
        try (Fixture f=new Fixture((s,n)->{
            new DataInputStream(s.getInputStream()).readFully(new byte[3]);
            for (byte b : new byte[] {5,0,5,0,0,1,0,0,0,0,0,0}) {
                s.getOutputStream().write(b); s.getOutputStream().flush(); Thread.sleep(60);
            }
        })) {
            long started=System.nanoTime();
            assertFalse(ShardProbe.INSTANCE.check(f.port(),200));
            assertTrue("one total deadline, not one per byte", (System.nanoTime()-started)/1000000 < 900);
        }
    }
    @Test public void endpointFallbackSharesOneBudget() throws Exception {
        try (Fixture f=new Fixture((s,n)->{
            socks(s,false,5); request(s); Thread.sleep(100);
            response(s,n==1 ? "HTTP/1.1 500 unavailable" : "HTTP/1.1 204 No Content");
        })) {
            long started=System.nanoTime();
            assertFalse(ShardProbe.INSTANCE.check(f.port(),170));
            assertTrue((System.nanoTime()-started)/1000000 < 900);
        }
    }
    @Test public void zeroBudgetDoesNotOpenAnySocket() throws Exception {
        try (Fixture f=new Fixture((s,n)->{ throw new AssertionError("unexpected connection"); })) {
            assertFalse(ShardProbe.INSTANCE.check(f.port(),0));
            assertEquals(0,f.accepted.get());
        }
    }
}
