package com.molido.vpn;

import java.net.URI;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline import checks only, not proof of engine compatibility or internet access. */
public final class VpnProfilePreflight {
    public static final int MAX_BYTES = 512 * 1024;
    private VpnProfilePreflight() {}

    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final String reason;
        Invalid(String reason) { super(reason); this.reason = reason; }
    }
    private static Invalid invalid(String reason) { return new Invalid(reason); }

    /** Bounded read using APIs available on Android 26. */
    public static byte[] readBounded(InputStream input, int maximum) throws IOException {
        if (maximum < 0 || maximum > MAX_BYTES * 2) throw new IllegalArgumentException("Invalid limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (output.size() <= maximum) {
            int n = input.read(chunk, 0, Math.min(chunk.length, maximum + 1 - output.size()));
            if (n < 0) break;
            if (n == 0) {
                int b = input.read();
                if (b < 0) break;
                output.write(b);
            } else output.write(chunk, 0, n);
        }
        if (output.size() > maximum) throw invalid("size");
        return output.toByteArray();
    }

    public static final class OpenVpn {
        public final String content;
        public final int remoteCount;
        private OpenVpn(String content, int remoteCount) {
            this.content = content;
            this.remoteCount = remoteCount;
        }
        // No toString(): the payload may contain a private key.
    }
    private static final Set<String> INLINE = new HashSet<>(Arrays.asList(
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "extra-certs", "auth-user-pass"));
    private static final Set<String> OPTIONS = new HashSet<>(Arrays.asList(
        "client", "tls-client", "dev", "dev-type", "proto", "remote", "port", "rport",
        "remote-random", "resolv-retry", "nobind", "persist-key", "persist-tun", "float",
        "auth-user-pass", "auth-nocache", "cipher", "data-ciphers", "data-ciphers-fallback",
        "auth", "remote-cert-tls", "verify-x509-name", "tls-version-min", "tls-version-max",
        "tls-cipher", "tls-ciphersuites", "key-direction", "reneg-sec", "verb", "mute",
        "mute-replay-warnings", "explicit-exit-notify", "connect-retry", "connect-retry-max",
        "connect-timeout", "server-poll-timeout", "ping", "ping-restart", "ping-exit",
        "keepalive", "tun-mtu", "mssfix", "sndbuf", "rcvbuf", "fast-io", "route",
        "route-ipv6", "route-nopull", "redirect-gateway", "redirect-private", "dhcp-option",
        "pull", "pull-filter", "setenv", "comp-lzo", "compress", "allow-compression",
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "extra-certs"));
    private static final Set<String> PROTOCOLS = new HashSet<>(Arrays.asList(
        "udp", "udp4", "udp6", "tcp", "tcp4", "tcp6", "tcp-client", "tcp4-client", "tcp6-client"));

    public static OpenVpn openVpn(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("size");
        final String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF", "");
        } catch (Exception e) { throw invalid("encoding"); }
        if (content.indexOf('\0') >= 0) throw invalid("encoding");
        String block = null;
        boolean blockHasData = false;
        int remotes = 0;
        boolean client = false;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (block != null) {
                if (line.equals("</" + block + ">")) {
                    if (!blockHasData) throw invalid("inline");
                    block = null;
                } else {
                    if (line.startsWith("<") || line.endsWith(">")) throw invalid("inline");
                    if (!line.isEmpty()) blockHasData = true;
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("<")) {
                if (!line.endsWith(">")) throw invalid("inline");
                block = line.substring(1, line.length() - 1);
                if (!INLINE.contains(block)) throw invalid("unsupported");
                blockHasData = false;
                continue;
            }
            List<String> args = words(line);
            if (args.isEmpty()) continue;
            String option = args.get(0).replaceFirst("^--", "");
            if (!OPTIONS.contains(option)) throw invalid("unsupported");
            if (INLINE.contains(option) && args.size() > 1) throw invalid("external");
            if (!option.equals("auth-user-pass") && INLINE.contains(option)) throw invalid("external");
            if (option.equals("client") || option.equals("tls-client")) client = true;
            if (option.equals("dev") && (args.size() != 2 || !args.get(1).equals("tun"))) throw invalid("device");
            if (option.equals("dev-type") && (args.size() != 2 || !args.get(1).equals("tun"))) throw invalid("device");
            if (option.equals("proto") && (args.size() != 2 || !PROTOCOLS.contains(args.get(1)))) throw invalid("transport");
            if (option.equals("port") || option.equals("rport")) {
                if (args.size() != 2) throw invalid("remote");
                port(args.get(1));
            }
            if (option.equals("remote")) {
                if (args.size() < 2 || args.size() > 4 || !host(args.get(1))) throw invalid("remote");
                if (args.size() >= 3) port(args.get(2));
                if (args.size() == 4 && !PROTOCOLS.contains(args.get(3))) throw invalid("transport");
                if (++remotes > 64) throw invalid("remote");
            }
        }
        if (block != null) throw invalid("inline");
        if (!client || remotes == 0) throw invalid("client");
        return new OpenVpn(content, remotes);
    }

    /** No network lookup or credentials. Authentication belongs to the future engine. */
    public static String openConnect(String input) {
        String value = input.trim();
        if (value.length() == 0 || value.length() > 2048) throw invalid("gateway");
        if (!value.contains("://")) value = "https://" + value;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":"))
                throw invalid("gateway");
            String path = uri.getRawPath();
            if (path != null && path.matches("(?i).*%(00|0a|0d).*")) throw invalid("gateway");
            return uri.toASCIIString();
        } catch (Exception e) { throw invalid("gateway"); }
    }
    private static void port(String value) {
        try {
            if (!value.matches("[0-9]{1,5}")) throw invalid("remote");
            int p = Integer.parseInt(value);
            if (p < 1 || p > 65535) throw invalid("remote");
        } catch (NumberFormatException e) { throw invalid("remote"); }
    }
    private static boolean host(String value) {
        if (value.length() > 253 || value.isEmpty() || value.startsWith("-")) return false;
        return value.matches("[A-Za-z0-9_.:\\[\\]-]+");
    }
    private static List<String> words(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean escaped = false, started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) { word.append(c); escaped = false; started = true; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (quote != 0) {
                if (c == quote) quote = 0; else word.append(c);
                started = true; continue;
            }
            if (c == '\'' || c == '"') { quote = c; started = true; continue; }
            if (c == '#' || c == ';') break;
            if (Character.isWhitespace(c)) {
                if (started) { result.add(word.toString()); word.setLength(0); started = false; }
            } else { word.append(c); started = true; }
        }
        if (escaped || quote != 0) throw invalid("syntax");
        if (started) result.add(word.toString());
        return Collections.unmodifiableList(result);
    }
}
