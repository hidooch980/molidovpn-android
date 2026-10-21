package com.molido.vpn.networkprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import ca.psiphon.Tun2SocksJniLoader;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs the production native packet stack against loopback-only SOCKS fixtures.
 * The socketpair replaces a TUN, so this is NOT an OS VPN/lockdown leak test. */
public final class NetworkProbe extends Instrumentation {
    private static void need(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static byte[] ip(String value) throws Exception { return InetAddress.getByName(value).getAddress(); }
    private static int u16(byte[] b,int p) { return (b[p]&255)*256+(b[p+1]&255); }
    private static long u32(byte[] b,int p) { return ((long)u16(b,p)<<16)|u16(b,p+2); }
    private static void w16(byte[] b,int p,int v) { b[p]=(byte)(v>>8); b[p+1]=(byte)v; }
    private static void w32(byte[] b,int p,long v) { w16(b,p,(int)(v>>16)); w16(b,p+2,(int)v); }
    private static int sum(byte[] b) {
        long n=0; for(int i=0;i<b.length;i+=2) n+=(b[i]&255)*256+(i+1<b.length?(b[i+1]&255):0);
        while((n>>16)!=0) n=(n&65535)+(n>>16);
        return (int)(~n)&65535;
    }
    private static byte[] pseudo(byte[] src,byte[] dst,int protocol,int length) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); b.write(src); b.write(dst);
        DataOutputStream out=new DataOutputStream(b);
        if(src.length==16) { out.writeInt(length); out.write(new byte[3]); out.writeByte(protocol); }
        else { out.writeByte(0); out.writeByte(protocol); out.writeShort(length); }
        return b.toByteArray();
    }
    private static byte[] concat(byte[] a,byte[] b) { byte[] r=Arrays.copyOf(a,a.length+b.length); System.arraycopy(b,0,r,a.length,b.length); return r; }
    private static byte[] packet(boolean v6,boolean tcp,int sport,int dport,long seq,long ack,int flags,byte[] payload,boolean dns) throws Exception {
        byte[] src=ip(v6?"fd42:6d6f:6c69::1":"10.0.0.1");
        byte[] dst=ip(v6?"2001:db8::10":(dns?"10.0.0.2":"198.18.0.10"));
        int h=tcp?20:8, protocol=tcp?6:17;
        byte[] body=new byte[h+payload.length]; w16(body,0,sport); w16(body,2,dport);
        if(tcp) { w32(body,4,seq); w32(body,8,ack); body[12]=0x50; body[13]=(byte)flags; w16(body,14,60000); }
        else w16(body,4,body.length);
        System.arraycopy(payload,0,body,h,payload.length);
        int checksum=sum(concat(pseudo(src,dst,protocol,body.length),body));
        w16(body,tcp?16:6,checksum==0?65535:checksum);
        byte[] header=new byte[v6?40:20];
        if(v6) { header[0]=0x60; w16(header,4,body.length); header[6]=(byte)protocol; header[7]=64; System.arraycopy(src,0,header,8,16); System.arraycopy(dst,0,header,24,16); }
        else { header[0]=0x45; w16(header,2,header.length+body.length); header[8]=64; header[9]=(byte)protocol; System.arraycopy(src,0,header,12,4); System.arraycopy(dst,0,header,16,4); w16(header,10,sum(header)); }
        return concat(header,body);
    }
    private static final class Link implements AutoCloseable {
        final ParcelFileDescriptor peer;
        final Thread nativeThread;
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        Link(int socksPort,boolean dnsOnly) throws Exception {
            // Datagram boundaries model one packet per TUN read/write exactly.
            FileDescriptor a=new FileDescriptor(),b=new FileDescriptor();
            Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM,0,a,b);
            int fd=ParcelFileDescriptor.dup(a).detachFd();
            peer=ParcelFileDescriptor.dup(b); Os.close(a); Os.close(b);
            nativeThread=new Thread(()->{
                try { Tun2SocksJniLoader.runTun2Socks(fd,1400,"10.0.0.2","255.255.255.0","fd42:6d6f:6c69::2","127.0.0.1:"+socksPort,"127.0.0.1:7300",1,dnsOnly?1:0); }
                catch(Throwable e) { failure.set(e); }
            },"real-tun2socks-fixture");
            nativeThread.start();
        }
        void send(byte[] b) throws Exception {
            int p=0; while(p<b.length) p+=Os.write(peer.getFileDescriptor(),b,p,b.length-p);
        }
        byte[] next(long deadline) throws Exception {
            while(true) {
                long ms=(deadline-System.nanoTime())/1000000;
                if(ms<=0) throw new SocketTimeoutException("native packet deadline");
                StructPollfd fd=new StructPollfd(); fd.fd=peer.getFileDescriptor(); fd.events=(short)OsConstants.POLLIN;
                if(Os.poll(new StructPollfd[]{fd},(int)Math.min(ms,5000))<=0) continue;
                byte[] b=new byte[1500];int got=Os.read(peer.getFileDescriptor(),b,0,b.length);
                if(got<=0) throw new EOFException("native packet stream ended");
                int version=(b[0]&255)>>4; need(version==4||version==6,"IP version");
                int n=version==6?40+u16(b,4):u16(b,2);
                need(n==got&&n<=1400,"packet boundary/MTU");
                return Arrays.copyOf(b,got);
            }
        }
        @Override public void close() throws Exception {
            Tun2SocksJniLoader.terminateTun2Socks(); nativeThread.join(5000);
            peer.close();
            need(!nativeThread.isAlive(),"native instance failed to unwind");
            if(failure.get()!=null) throw new AssertionError("native exception",failure.get());
        }
    }
    private static int transport(byte[] p) { return (p[0]&0xf0)==0x60?40:(p[0]&15)*4; }
    private static void validate(byte[] p,int protocol) throws Exception {
        boolean v6=(p[0]&0xf0)==0x60; int h=transport(p);
        need((p[v6?6:9]&255)==protocol,"packet protocol");
        if(!v6) need(sum(Arrays.copyOf(p,h))==0,"IPv4 header checksum");
        byte[] src=Arrays.copyOfRange(p,v6?8:12,v6?24:16),dst=Arrays.copyOfRange(p,v6?24:16,v6?40:20);
        need(sum(concat(pseudo(src,dst,protocol,p.length-h),Arrays.copyOfRange(p,h,p.length)))==0,"transport checksum");
    }
    private static void tcp(Link link,boolean v6,int sport) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        link.send(packet(v6,true,sport,443,100,0,2,new byte[0],false));
        byte[] syn;
        while(true) { syn=link.next(deadline); int h=transport(syn); if((syn[(h==40)?6:9]&255)==6&&u16(syn,h+2)==sport&&(syn[h+13]&0x12)==0x12) break; }
        validate(syn,6); int h=transport(syn); long serverSeq=u32(syn,h+4);
        byte[] marker=(v6?"NATIVE-TCP6-FIXTURE":"NATIVE-TCP4-FIXTURE").getBytes(StandardCharsets.US_ASCII);
        link.send(packet(v6,true,sport,443,101,serverSeq+1,0x18,marker,false));
        while(true) {
            byte[] p=link.next(deadline); h=transport(p);
            if((p[h==40?6:9]&255)!=6||u16(p,h+2)!=sport) continue;
            int body=h+((p[h+12]>>4)&15)*4;
            if(p.length==body) continue;
            validate(p,6); need(Arrays.equals(marker,Arrays.copyOfRange(p,body,p.length)),"TCP payload changed"); return;
        }
    }
    private static byte[] dnsQuery() throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        b.write(new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0});
        for(String label:new String[]{"fixture","invalid"}) { byte[] v=label.getBytes(StandardCharsets.US_ASCII); b.write(v.length); b.write(v); }
        b.write(new byte[]{0,0,28,0,1}); return b.toByteArray();
    }
    private static long udp(Link link,boolean v6,int sport,boolean dns) throws Exception {
        byte[] payload=dns?dnsQuery():(v6?"NATIVE-UDP6":"NATIVE-UDP4").getBytes(StandardCharsets.US_ASCII);
        byte[] expected=payload.clone(); if(dns) expected[2]|=(byte)0x80;
        long started=System.nanoTime(),deadline=started+TimeUnit.SECONDS.toNanos(5);
        link.send(packet(v6,false,sport,dns?53:9000,0,0,0,payload,dns));
        while(true) {
            byte[] p=link.next(deadline); int h=transport(p);
            if((p[h==40?6:9]&255)!=17||u16(p,h+2)!=sport) continue;
            validate(p,17); need(Arrays.equals(expected,Arrays.copyOfRange(p,h+8,p.length)),"UDP/DNS payload changed");
            return (System.nanoTime()-started)/1000000;
        }
    }
    private static final class ProxyFixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final AtomicInteger tcp4=new AtomicInteger(),tcp6=new AtomicInteger(),udp4=new AtomicInteger(),udp6=new AtomicInteger(),dns=new AtomicInteger();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        volatile int delayMs;
        final Thread listener;
        ProxyFixture() throws Exception {
            listener=new Thread(()->{
                while(!server.isClosed()) {
                    try {
                        Socket s=server.accept(); sockets.add(s); s.setSoTimeout(10000);
                        Thread t=new Thread(()->{try(s){serve(s);}catch(SocketException|EOFException expected){}catch(Throwable e){failure.compareAndSet(null,e);}},"socks-fixture-client");
                        t.setDaemon(true); t.start();
                    }catch(IOException done){break;}
                }
            },"socks-fixture-listener"); listener.setDaemon(true); listener.start();
        }
        void serve(Socket s) throws Exception {
            DataInputStream in=new DataInputStream(s.getInputStream()); OutputStream out=s.getOutputStream();
            need(in.readUnsignedByte()==5,"SOCKS greeting version"); int count=in.readUnsignedByte(); byte[] methods=new byte[count];in.readFully(methods);
            out.write(new byte[]{5,0});out.flush();
            need(in.readUnsignedByte()==5&&in.readUnsignedByte()==1&&in.readUnsignedByte()==0,"SOCKS CONNECT");
            int type=in.readUnsignedByte(); need(type==1||type==4,"native destination family");
            byte[] addr=new byte[type==4?16:4];in.readFully(addr);int port=in.readUnsignedShort();
            out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();
            if(port==7300) {
                need(Arrays.equals(addr,ip("127.0.0.1")),"UDPGW must stay behind SOCKS");
                while(true) {
                    int lo=in.readUnsignedByte(),hi=in.readUnsignedByte(),len=lo+(hi<<8); byte[] body=new byte[len];in.readFully(body);
                    need(len>=3,"UDPGW header"); int flags=body[0]&255;
                    if((flags&1)!=0)continue;
                    boolean v6=(flags&8)!=0;int p=3+(v6?18:6);need(len>p,"UDPGW payload");
                    if((flags&4)!=0){dns.incrementAndGet();need(u16(body,len-4)==28,"AAAA query lost");body[p+2]|=(byte)0x80;}
                    else if(v6)udp6.incrementAndGet();else udp4.incrementAndGet();
                    if(delayMs>0)Thread.sleep(delayMs);
                    out.write(lo);out.write(hi);out.write(body);out.flush();
                }
            } else {
                need(port==443,"unexpected native direct endpoint");
                need(Arrays.equals(addr,ip(type==4?"2001:db8::10":"198.18.0.10")),"native destination changed");
                (type==4?tcp6:tcp4).incrementAndGet();
                byte[] buf=new byte[4096];int n;while((n=in.read(buf))>=0){out.write(buf,0,n);out.flush();}
            }
        }
        @Override public void close() throws Exception {
            server.close(); for(Socket s:sockets)s.close(); listener.join(2000);
            if(failure.get()!=null)throw new AssertionError("proxy fixture",failure.get());
        }
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle report=new Bundle();StringBuilder details=new StringBuilder();
        try {
            // Two genuine native restarts catch stale reactor/listener globals.
            for(int cycle=0;cycle<2;cycle++) {
                try(ProxyFixture proxy=new ProxyFixture();Link link=new Link(proxy.server.getLocalPort(),false)) {
                    tcp(link,false,40000);tcp(link,true,40001);
                    udp(link,false,41000,false);udp(link,true,41001,false);udp(link,false,41002,true);
                    proxy.delayMs=120;
                    long rtt=udp(link,true,41003,false);need(rtt>=90&&rtt<3000,"injected delay not measured");
                    need(proxy.tcp4.get()==1&&proxy.tcp6.get()==1,"both TCP families not proxied");
                    need(proxy.udp4.get()>0&&proxy.udp6.get()>0&&proxy.dns.get()==1,"UDP/DNS fixture coverage");
                    details.append("cycle ").append(cycle).append(": TCP4 TCP6 UDP4 UDP6 DNS_AAAA checksums PASS; injected-delay RTT=").append(rtt).append("ms\n");
                }
            }
            try(ProxyFixture proxy=new ProxyFixture();Link link=new Link(proxy.server.getLocalPort(),true)) {
                link.send(packet(false,false,42000,9000,0,0,0,new byte[]{1,2,3},false));
                Thread.sleep(200);
                udp(link,false,42001,true);
                need(proxy.udp4.get()==0&&proxy.udp6.get()==0&&proxy.dns.get()==1,"Tor DNS-only UDP gate");
                details.append("DNS-only UDP suppression with AAAA DNS retained PASS\n");
            }
            report.putString("stream","\nMOLIDO_NETWORK_PROBE_OK\n"+details+"Local packet fixtures only; no OS VPN leak or real-carrier quality claim.\n");
            finish(Activity.RESULT_OK,report);
        }catch(Throwable failure){
            report.putString("stream","\nMOLIDO_NETWORK_PROBE_FAILED: "+failure+"\n"+details);
            finish(Activity.RESULT_CANCELED,report);
        }
    }
}
