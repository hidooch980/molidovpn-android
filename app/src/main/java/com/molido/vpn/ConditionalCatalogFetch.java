package com.molido.vpn;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ConditionalCatalogFetch {
    private ConditionalCatalogFetch() {}
    private static final class Cached {
        final String body, etag, modified;
        Cached(String b, String e, String m) {
            body = b; etag = e; modified = m;
        }
    }
    private static final Map<String, Cached> CACHE =
        new LinkedHashMap<>(24, .75f, true);

    public static String get(String url) {
        return get(url, 2_000_000);
    }

    public static String get(String url, int maxChars) {
        if (maxChars <= 0) return null;
        Cached old;
        synchronized (CACHE) { old = CACHE.get(url); }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (old != null) {
                if (old.etag != null)
                    c.setRequestProperty("If-None-Match", old.etag);
                if (old.modified != null)
                    c.setRequestProperty("If-Modified-Since", old.modified);
            }
            int status = c.getResponseCode();
            if (status == 304)
                return old != null && old.body.length() <= maxChars
                    ? old.body : null;
            if (status != 200) return null;
            StringBuilder body = new StringBuilder();
            try (Reader reader = new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8)) {
                char[] block = new char[8192];
                int n;
                while ((n = reader.read(block)) != -1) {
                    if (body.length() + n > maxChars) return null;
                    body.append(block, 0, n);
                }
            }
            String text = body.toString();
            if (text.trim().isEmpty()) return null;
            synchronized (CACHE) {
                CACHE.put(url, new Cached(text,
                    c.getHeaderField("ETag"),
                    c.getHeaderField("Last-Modified")));
                int total = 0;
                for (Cached item : CACHE.values())
                    total += item.body.length();
                while (!CACHE.isEmpty() &&
                        (CACHE.size() > 24 || total > 2_000_000)) {
                    String first = CACHE.keySet().iterator().next();
                    total -= CACHE.remove(first).body.length();
                }
            }
            return text;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
