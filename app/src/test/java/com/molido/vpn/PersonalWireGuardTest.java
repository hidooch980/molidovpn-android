package com.molido.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class PersonalWireGuardTest {
    private static String key(int seed) {byte[] b=new byte[32];java.util.Arrays.fill(b,(byte)seed);return Base64.getEncoder().encodeToString(b);}
    private static final String CONFIG="[Interface]\nPrivateKey = "+key(7)+"\nAddress = 10.2.0.2/32\nDNS = 10.2.0.1\n[Peer]\nPublicKey = "+key(9)+"\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 198.51.100.8:51820\n";
    private PersonalWireGuard.Profile parse(String s){return PersonalWireGuard.parse(s.getBytes(StandardCharsets.UTF_8));}
    private void rejects(String s){try{parse(s);fail("Unsupported profile accepted");}catch(PersonalWireGuard.Invalid e){assertFalse(e.getMessage().contains(key(7)));assertFalse(e.getMessage().contains("198.51.100"));}}
    @Test public void protonIpv4OnlyExportRetainsIdentityAndDns(){
        PersonalWireGuard.Profile p=parse(CONFIG);assertEquals("10.2.0.2",p.ipv4);assertEquals("",p.ipv6);
        assertEquals("198.51.100.8:51820",p.endpoint);assertEquals("10.2.0.1",p.dns.get(0));
        assertEquals(key(7),p.privateKey);assertEquals(key(9),p.publicKey);
        assertFalse(p.toString().contains(key(7)));
    }
    @Test public void dualStackAndIpv6EndpointRemainDistinct(){
        PersonalWireGuard.Profile p=parse(CONFIG.replace("10.2.0.2/32","10.2.0.2/32, 2001:db8:1::2/128").replace("10.2.0.1\n","10.2.0.1, 2001:db8:1::1\n").replace("198.51.100.8:51820","[2001:db8::4]:51820"));
        assertEquals("2001:db8:1::2",p.ipv6);assertEquals(2,p.dns.size());
    }
    @Test public void acceptsBomCrlfCommentsMtuAndDefaultKeepalive(){parse("\uFEFF"+CONFIG.replace("\n","\r\n")+"PersistentKeepalive=25 # compatible\r\n");parse(CONFIG.replace("[Peer]","MTU=1420\n[Peer]"));}
    @Test public void rejectsScriptsPskObfuscationAndUnknownOptions(){
        rejects(CONFIG+"PresharedKey="+key(1));rejects(CONFIG+"FutureOption=1");rejects(CONFIG.replace("[Peer]","PostUp = curl example.org\n[Peer]"));rejects(CONFIG.replace("[Peer]","Jc=5\n[Peer]"));
    }
    @Test public void rejectsPartialRoutesAndDuplicateSections(){rejects(CONFIG.replace("0.0.0.0/0","10.0.0.0/8"));rejects(CONFIG+"[Peer]\nPublicKey="+key(1));rejects(CONFIG.replace("[Peer]","[Interface]\n[Peer]"));rejects(CONFIG+"Endpoint=198.51.100.9:443\n");}
    @Test public void rejectsMalformedKeysPortsAddressesAndDns(){
        rejects(CONFIG.replace(key(7),"not-a-key"));rejects(CONFIG.replace(key(7),key(0)));rejects(CONFIG.replace(":51820",":0"));rejects(CONFIG.replace(":51820",":65536"));
        rejects(CONFIG.replace("10.2.0.2/32","10.999.0.2/32"));rejects(CONFIG.replace("10.2.0.2/32","10.2.0.2/33"));rejects(CONFIG.replace("198.51.100.8","vpn.example.org"));
        rejects(CONFIG.replace("DNS = 10.2.0.1\n",""));rejects(CONFIG.replace("DNS = 10.2.0.1","DNS = resolver.example.org"));rejects(CONFIG.replace("DNS = 10.2.0.1","DNS = 2001:db8::1"));
    }
    @Test public void rejectsUnsupportedMtuKeepaliveAndDualStackRouteMismatch(){rejects(CONFIG.replace("[Peer]","MTU=1200\n[Peer]"));rejects(CONFIG+"PersistentKeepalive=0");rejects(CONFIG.replace("10.2.0.2/32","10.2.0.2/32, 2001:db8::2/128").replace(", ::/0",""));}
    @Test public void rejectsOversizeAndInvalidUtf8(){try{PersonalWireGuard.parse(new byte[PersonalWireGuard.MAX_BYTES+1]);fail();}catch(PersonalWireGuard.Invalid expected){}try{PersonalWireGuard.parse(new byte[]{(byte)0xc3,0x28});fail();}catch(PersonalWireGuard.Invalid expected){}}
}
