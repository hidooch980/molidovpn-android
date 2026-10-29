package com.molido.vpn;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.LongSupplier;

public final class ConditionalCatalogFetch {
    private ConditionalCatalogFetch() {}
    private static final long FRESH_CAP_MS = 300_000L;
    private static final long FAILURE_DELAY_MS = 300_000L;
    private static final long RETRY_CAP_MS = 3_600_000L;
    private static final class Cached {
        final String body, etag, modified;
        final long until;
        Cached(String b, String e, String m, long u) {
            body = b; etag = e; modified = m; until = u;
        }
    }
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(24, .75f, true);
    private static final Map<String, Long> RETRY = new LinkedHashMap<>();
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    static LongSupplier clock = System::currentTimeMillis;

    public static String get(String url) { return get(url, 2_000_000); }

    public static String get(String url, int maxChars) {
        if (maxChars <= 0) return null;
        Cached old;
        long now = clock.getAsLong();
        synchronized (CACHE) {
            old = CACHE.get(url);
            if (old != null && old.until > now && old.until - now <= FRESH_CAP_MS)
                return old.body.length() <= maxChars ? old.body : null;
            Long retry = RETRY.get(url);
            if (retry != null && retry > now && retry - now <= RETRY_CAP_MS) return null;
            RETRY.remove(url);
            if (!IN_FLIGHT.add(url)) return null;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (old != null) {
                if (old.etag != null) c.setRequestProperty("If-None-Match", old.etag);
                if (old.modified != null) c.setRequestProperty("If-Modified-Since", old.modified);
            }
            int status = c.getResponseCode();
            if (status == 304 && old != null) {
                store(url, old.body, c.getHeaderField("ETag") != null ? c.getHeaderField("ETag") : old.etag,
                    c.getHeaderField("Last-Modified") != null ? c.getHeaderField("Last-Modified") : old.modified,
                    c.getHeaderField("Cache-Control"));
                return old.body.length() <= maxChars ? old.body : null;
            }
            if (status != 200) {
                defer(url, c.getHeaderField("Retry-After"));
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (Reader reader = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
                char[] block = new char[8192];
                int n;
                while ((n = reader.read(block)) != -1) {
                    if (body.length() + n > maxChars) {
                        defer(url, null);
                        return null;
                    }
                    body.append(block, 0, n);
                }
            }
            String text = body.toString();
            if (text.trim().isEmpty()) {
                defer(url, null);
                return null;
            }
            store(url, text, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), c.getHeaderField("Cache-Control"));
            return text;
        } catch (Exception ignored) {
            defer(url, null);
            return null;
        } finally {
            if (c != null) c.disconnect();
            synchronized (CACHE) { IN_FLIGHT.remove(url); }
        }
    }

    private static void store(String url, String text, String etag, String modified, String control) {
        String cc = control == null ? "" : control.toLowerCase(Locale.US);
        long ttl = 60_000L;
        if (cc.contains("no-cache") || cc.contains("no-store")) ttl = 0L;
        else {
            for (String part : cc.split(",")) {
                String p = part.trim();
                if (p.startsWith("max-age=")) {
                    try { ttl = Math.min(300L, Math.max(0L, Long.parseLong(p.substring(8).replace("\"", "")))) * 1000L; }
                    catch (NumberFormatException ignored) { ttl = 0L; }
                    break;
                }
            }
        }
        synchronized (CACHE) {
            RETRY.remove(url);
            if (cc.contains("no-store")) { CACHE.remove(url); return; }
            CACHE.put(url, new Cached(text, etag, modified, clock.getAsLong() + ttl));
            int total = 0;
            for (Cached item : CACHE.values()) total += item.body.length();
            while (!CACHE.isEmpty() && (CACHE.size() > 24 || total > 2_000_000)) {
                String first = CACHE.keySet().iterator().next();
                total -= CACHE.remove(first).body.length();
            }
        }
    }

    private static void defer(String url, String retryAfter) {
        long now = clock.getAsLong();
        long delay = FAILURE_DELAY_MS;
        if (retryAfter != null && retryAfter.length() <= 128) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                delay = Math.min(3600L, Math.max(60L, seconds)) * 1000L;
            } catch (NumberFormatException ignored) {
                try {
                    SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
                    fmt.setLenient(false);
                    Date date = fmt.parse(retryAfter);
                    if (date != null) delay = Math.max(60_000L, Math.min(RETRY_CAP_MS, date.getTime() - now));
                } catch (Exception ignoredDate) {}
            }
        }
        synchronized (CACHE) {
            RETRY.put(url, now + delay);
            while (RETRY.size() > 64) RETRY.remove(RETRY.keySet().iterator().next());
        }
    }

    static void resetForTests() {
        synchronized (CACHE) { CACHE.clear(); RETRY.clear(); IN_FLIGHT.clear(); }
        clock = System::currentTimeMillis;
    }
}
