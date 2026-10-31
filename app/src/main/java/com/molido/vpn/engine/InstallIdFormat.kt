package com.molido.vpn.engine

/**
 * Install ID: `MLD-XXXX-XXXX`, 8 random Crockford base32 characters (40 bits). Random on first launch:
 * no IMEI, Android ID, MAC or anything else derived from the device or the person.
 */
object InstallIdFormat {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val RE = Regex("^MLD-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$")

    fun generate(random: java.util.Random = java.security.SecureRandom()): String {
        val c = CharArray(8) { ALPHABET[random.nextInt(32)] }
        return "MLD-" + String(c, 0, 4) + "-" + String(c, 4, 4)
    }

    fun valid(id: String?): Boolean = id != null && RE.matches(id)

    /** Typed by a person: case, spaces and the look-alikes Crockford folds (O→0, I/L→1). */
    fun normalize(input: String): String? {
        val body = input.trim().uppercase().replace(" ", "").removePrefix("MLD").replace("-", "")
            .replace('O', '0').replace('I', '1').replace('L', '1')
        if (body.length != 8) return null
        val id = "MLD-" + body.substring(0, 4) + "-" + body.substring(4)
        return id.takeIf { valid(it) }
    }
}

/**
 * Local ring buffer of connection events for one install (no personal data, no configs, no secrets:
 * messages pass [scrub]). Pure part: JSON lines in and out, capped at [MAX] entries.
 */
object DiagBuffer {
    const val MAX = 80
    private val URI = Regex("""\b(vless|vmess|trojan|ss|ssr|hysteria2?|hy2|tuic|anytls|wireguard|socks5?|https?)://\S+""", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b""")
    private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val KEYISH = Regex("""\b(key|pbk|password|pass|token|secret|sid|uuid|id)=([^&\s]+)""", RegexOption.IGNORE_CASE)
    private val LONGB64 = Regex("""[A-Za-z0-9+/=_-]{32,}""")

    /** Strip anything that could be a config, credential or full address. */
    fun scrub(text: String): String = text
        .replace(URI) { "${it.groupValues[1]}://…" }
        .replace(UUID, "<uuid>")
        .replace(KEYISH) { "${it.groupValues[1]}=…" }
        .replace(IPV4) { "${it.groupValues[1]}.${it.groupValues[2]}.x.x" }
        .replace(LONGB64, "<…>")
        .take(400)

    fun event(at: Long, kind: String, method: String?, net: String?, msg: String): String =
        org.json.JSONObject().put("at", at / 1000).put("kind", kind.take(24))
            .put("method", method?.take(32) ?: org.json.JSONObject.NULL).put("net", net?.take(16) ?: org.json.JSONObject.NULL)
            .put("msg", scrub(msg)).toString()

    fun append(lines: List<String>, line: String): List<String> = (lines + line).takeLast(MAX)

    /** Events not yet uploaded (after [sentAt], epoch seconds). */
    fun pending(lines: List<String>, sentAt: Long): List<String> =
        lines.filter { runCatching { org.json.JSONObject(it).optLong("at") > sentAt }.getOrDefault(false) }
}
