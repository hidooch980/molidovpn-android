package com.molido.vpn

import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** Validate share-link settings without silently replacing explicit transport choices. */
internal data class XhttpProfile(val mode: String, val extra: String) {
    companion object {
        private val modes = setOf("auto", "packet-up", "stream-up", "stream-one")

        fun parse(rawMode: String, rawExtra: String): XhttpProfile? {
            val mode = rawMode.lowercase(Locale.US).ifEmpty { "auto" }
            if (mode !in modes || rawExtra.length > 65_536) return null
            if (rawExtra.isBlank()) return XhttpProfile(mode, "")
            return runCatching {
                val tokener = JSONTokener(rawExtra)
                val extra = tokener.nextValue() as? JSONObject ?: return null
                if (tokener.nextClean() != '\u0000') return null
                // Xray rejects Host inside XHTTP headers: use the URI's host field.
                val headers = extra.opt("headers")
                if (headers != null && headers != JSONObject.NULL) {
                    if (headers !is JSONObject) return null
                    for (name in headers.keys()) {
                        if (name.equals("host", ignoreCase = true) || headers.opt(name) !is String) return null
                    }
                }
                val download = extra.opt("downloadSettings")
                if (download != null && download != JSONObject.NULL) {
                    if (download !is JSONObject || mode == "stream-one") return null
                }
                // Keep the full object, including XMUX, padding and downstream settings.
                // This is import preflight, not a replacement for Xray's complete validator.
                XhttpProfile(mode, extra.toString())
            }.getOrNull()
        }
    }
}
