package com.molido.vpn

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress

/** Input validation only; this does not prove that a host runs an IKEv2 server. */
object Ikev2ProfileValidation {
    fun isValidServer(value: String): Boolean {
        val host = value.trim().trimEnd('.')
        if (host.isEmpty() || host.length > 253 || host.any { it.isWhitespace() }) return false
        if (host.contains(':')) {
            // A colon forces literal parsing; this does not perform a hostname lookup.
            if (!host.matches(Regex("^[0-9A-Fa-f:.]+$"))) return false
            return try { InetAddress.getByName(host) is Inet6Address }
            catch (_: Exception) { false }
        }
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            return ipv4Parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        val ascii = try { IDN.toASCII(host) } catch (_: IllegalArgumentException) { return false }
        if (ascii.length > 253 || !ascii.contains('.')) return false
        return ascii.split('.').all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    fun hasEapCredentials(identity: String, username: String, password: String): Boolean =
        identity.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}
