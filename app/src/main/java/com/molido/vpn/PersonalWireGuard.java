package com.molido.vpn;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.*;

/** Strict offline importer for a single full-tunnel WireGuard peer (including Proton). */
public final class PersonalWireGuard {
    public static final int MAX_BYTES = 64 * 1024;
    private PersonalWireGuard() {}
    public static final class Invalid extends IllegalArgumentException {
        public final String reason;
        Invalid(String reason) { super(reason); this.reason = reason; }
    }
    public static final class Profile {
        public final String privateKey, publicKey, ipv4, ipv6, endpoint, content;
        public final List<String> dns;
        private Profile(Map<String,String> i, Map<String,String> p, String v4, String v6,
                        String endpoint, List<String> dns, String content) {
            privateKey=i.get("privatekey"); publicKey=p.get("publickey");
            ipv4=v4; ipv6=v6; this.endpoint=endpoint;
            this.dns=Collections.unmodifiableList(dns); this.content=content;
        }
        @Override public String toString() { return "PersonalWireGuard.Profile"; }
    }
    private static Invalid bad(String reason) { return new Invalid(reason); }
    private static final Set<String> IFACE = new HashSet<>(Arrays.asList("privatekey","address","dns","mtu"));
    private static final Set<String> PEER = new HashSet<>(Arrays.asList("publickey","allowedips","endpoint","persistentkeepalive"));
    public static Profile parse(byte[] bytes) {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw bad("size");
        final String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF", ""); }
        catch(Exception e){throw bad("encoding");}
        Map<String,String> iface=new HashMap<>(),peer=new HashMap<>(); Map<String,String> active=null;
        boolean sawInterface=false,sawPeer=false;
        for(String raw:text.split("\\r?\\n")) {
            String line=raw.split("#",2)[0].trim();
            if(line.isEmpty()||line.startsWith(";"))continue;
            if(line.equalsIgnoreCase("[Interface]")) {
                if(sawInterface||sawPeer)throw bad("sections"); sawInterface=true; active=iface;continue;
            }
            if(line.equalsIgnoreCase("[Peer]")) {
                if(!sawInterface||sawPeer)throw bad("sections");sawPeer=true;active=peer;continue;
            }
            int eq=line.indexOf('='); if(active==null||eq<1)throw bad("syntax");
            String key=line.substring(0,eq).trim().toLowerCase(Locale.ROOT),value=line.substring(eq+1).trim();
            if(!(active==iface?IFACE:PEER).contains(key))throw bad("unsupported");
            if(value.isEmpty()||active.put(key,value)!=null)throw bad("syntax");
        }
        if(!sawInterface||!sawPeer)throw bad("sections");
        key(iface.get("privatekey"));key(peer.get("publickey"));
        String v4="",v6="";
        for(String value:list(iface.get("address"))) {
            String[] parts=value.split("/",-1); if(parts.length!=2)throw bad("address");
            byte[] address=ip(parts[0]);int prefix=number(parts[1],"address");
            if(prefix<0||prefix>address.length*8)throw bad("address");
            boolean zero=true;for(byte b:address)zero&=b==0;
            if(zero)throw bad("address");
            if(address.length==4){if(!v4.isEmpty())throw bad("address");v4=parts[0];}
            else {if(!v6.isEmpty())throw bad("address");v6=parts[0];}
        }
        if(v4.isEmpty())throw bad("address");
        Set<String> routes=new HashSet<>(list(peer.get("allowedips")));
        if(!routes.contains("0.0.0.0/0")||(!v6.isEmpty()&&!routes.contains("::/0"))||
            routes.stream().anyMatch(r->!r.equals("0.0.0.0/0")&&!r.equals("::/0")))throw bad("routes");
        List<String> dns=new ArrayList<>();for(String d:list(iface.get("dns"))){ip(d);dns.add(d);}
        if(dns.isEmpty()||dns.size()>4||dns.stream().anyMatch(d->v6Empty(iface)&&d.contains(":")))throw bad("dns");
        int mtu=iface.containsKey("mtu")?number(iface.get("mtu"),"mtu"):1280;
        // The current native bridge uses MTU 1280. Never exceed a profile's smaller limit.
        if(mtu<1280||mtu>9000)throw bad("mtu");
        if(peer.containsKey("persistentkeepalive")&&number(peer.get("persistentkeepalive"),"keepalive")!=25)throw bad("keepalive");
        String endpoint=peer.get("endpoint");if(endpoint==null)throw bad("endpoint");
        String host,port;
        if(endpoint.startsWith("[")) {int end=endpoint.indexOf(']');if(end<0||!endpoint.substring(end).startsWith("]:"))throw bad("endpoint");host=endpoint.substring(1,end);port=endpoint.substring(end+2);if(ip(host).length!=16)throw bad("endpoint");}
        else {int colon=endpoint.indexOf(':');if(colon<1||colon!=endpoint.lastIndexOf(':'))throw bad("endpoint");host=endpoint.substring(0,colon);port=endpoint.substring(colon+1);if(ip(host).length!=4)throw bad("endpoint");}
        int n=number(port,"endpoint");if(n<1||n>65535)throw bad("endpoint");
        return new Profile(iface,peer,v4,v6,endpoint,dns,text);
    }
    private static boolean v6Empty(Map<String,String> iface){return !iface.getOrDefault("address","").contains(":");}
    private static List<String> list(String v){return v==null?Collections.emptyList():Arrays.asList(v.split("\\s*,\\s*",-1));}
    private static int number(String s,String reason){try{if(!s.matches("[0-9]{1,5}"))throw bad(reason);return Integer.parseInt(s);}catch(NumberFormatException e){throw bad(reason);}}
    private static void key(String s){try{if(s==null||!s.matches("[A-Za-z0-9+/]{43}=")||Base64.getDecoder().decode(s).length!=32)throw bad("key");byte[] b=Base64.getDecoder().decode(s);boolean zero=true;for(byte x:b)zero&=x==0;if(zero)throw bad("key");}catch(IllegalArgumentException e){throw bad("key");}}
    private static byte[] ip(String host) {
        try {
            if(host.contains(":")) {
                if(!host.matches("[0-9a-fA-F:]+"))throw bad("address");
                byte[] result=InetAddress.getByName(host).getAddress();if(result.length!=16)throw bad("address");return result;
            }
            String[] p=host.split("\\.",-1);if(p.length!=4)throw bad("address");byte[] out=new byte[4];
            for(int i=0;i<4;i++){if(!p[i].matches("0|[1-9][0-9]{0,2}"))throw bad("address");int v=Integer.parseInt(p[i]);if(v>255)throw bad("address");out[i]=(byte)v;}return out;
        }catch(Exception e){throw bad("address");}
    }
}
