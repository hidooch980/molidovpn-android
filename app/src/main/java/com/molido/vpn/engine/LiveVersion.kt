package com.molido.vpn.engine

import org.json.JSONObject

/**
 * `version.json` on the vpn-sub `live` branch: one hash per thing an app may need to refetch.
 * The app reads only this small file on its triggers and downloads a part when its hash moved.
 * An empty hash means "unknown" (the publisher could not read it) and never counts as a change.
 */
data class LiveVersion(
    val v: String,
    val flags: String = "",
    val notice: String = "",
    val release: String = "",
    val critical: Boolean = false,
    val resilience: String = "",
    val catalog: String = "",
    val at: Long = 0L,
) {
    enum class Part { FLAGS, NOTICE, RELEASE, RESILIENCE, CATALOG }

    fun toJson(): String = JSONObject().put("v", v).put("flags", flags).put("notice", notice)
        .put("release", release).put("critical", critical).put("resilience", resilience)
        .put("catalog", catalog).put("at", at).toString()

    companion object {
        fun parse(text: String?): LiveVersion? = try {
            val o = JSONObject(text ?: "")
            val v = o.optString("v")
            if (v.isEmpty()) null else LiveVersion(
                v = v,
                flags = o.optString("flags"),
                notice = o.optString("notice"),
                release = o.optString("release"),
                critical = o.optBoolean("critical", false),
                resilience = o.optString("resilience"),
                catalog = o.optString("catalog"),
                at = o.optLong("at", 0L),
            )
        } catch (_: Exception) {
            null
        }

        /** Parts whose hash moved. No previous copy: everything known counts as changed once. */
        fun changes(old: LiveVersion?, new: LiveVersion): Set<Part> {
            if (old != null && old.v == new.v) return emptySet()
            val out = LinkedHashSet<Part>()
            fun moved(o: String?, n: String) = n.isNotEmpty() && o != n
            if (moved(old?.flags, new.flags)) out += Part.FLAGS
            if (moved(old?.notice, new.notice)) out += Part.NOTICE
            if (moved(old?.release, new.release)) out += Part.RELEASE
            if (moved(old?.resilience, new.resilience)) out += Part.RESILIENCE
            if (moved(old?.catalog, new.catalog)) out += Part.CATALOG
            return out
        }
    }
}

/** Throttle for the cheap checks: at most one per [minGapMs], unless forced by a push. */
object LiveThrottle {
    fun due(lastAt: Long, now: Long, minGapMs: Long): Boolean = now - lastAt !in 0 until minGapMs
}

/**
 * Signature on a pushed owner change (Worker → ntfy.sh): ECDSA P-256 / SHA-256. WebCrypto signs in
 * IEEE P1363 (r‖s, 64 bytes); Java's verifier wants DER, converted here.
 */
object LiveSig {
    fun p1363ToDer(sig: ByteArray): ByteArray {
        require(sig.size == 64) { "P-256 signature must be 64 bytes" }
        fun int(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i] == 0.toByte()) i++
            val trimmed = b.copyOfRange(i, b.size)
            return if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
        }
        val r = int(sig.copyOfRange(0, 32))
        val s = int(sig.copyOfRange(32, 64))
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    fun verify(publicKeyB64: String, payload: String, sigB64: String): Boolean = try {
        val key = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(publicKeyB64)))
        val v = java.security.Signature.getInstance("SHA256withECDSA")
        v.initVerify(key)
        v.update(payload.toByteArray(Charsets.UTF_8))
        v.verify(p1363ToDer(java.util.Base64.getDecoder().decode(sigB64)))
    } catch (_: Exception) {
        false
    }
}
