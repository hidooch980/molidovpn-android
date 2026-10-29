package com.molido.vpn

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Last usable response per source, so a fast partial refresh cannot erase other sources. */
internal class SubscriptionSnapshots(private val directory: File) {
    companion object {
        private val lock = Any()
        internal const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val MAX_BYTES = 2_000_000

        fun writeAtomically(file: File, text: String) = synchronized(lock) {
            val atomic = AtomicFile(file)
            var stream: FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
        }

        fun readAtomically(file: File): String = synchronized(lock) {
            AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun file(source: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$hash.txt")
    }

    /** Called only after parsing a nonempty valid source response. Failed/empty fetches never replace it. */
    fun replace(source: String, canonicalBody: String, now: Long = System.currentTimeMillis()) {
        if (canonicalBody.isBlank() || canonicalBody.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return
        synchronized(lock) {
            directory.mkdirs()
            val target = file(source)
            val previous = runCatching { readAtomically(target) }.getOrNull()
            val at = previous?.substringBefore('\n')?.toLongOrNull() ?: 0L
            // A cancelled slow request can finish after the next poll. Its
            // older generation must not overwrite a newer usable response.
            if (at > now) return@synchronized
            // Polling an unchanged source every five seconds must not rewrite
            // megabytes to flash. Renew its timestamp at most once a minute.
            if (previous?.substringAfter('\n', "") == canonicalBody && now - at in 0 until 60_000L)
                return@synchronized
            writeAtomically(target, "$now\n$canonicalBody")
        }
    }

    fun read(sources: List<String>, now: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val active = sources.distinct().map { file(it) }
        val keep = active.flatMap { listOf(it.name, it.name + ".bak", it.name + ".new") }.toSet()
        directory.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        active.flatMap { file ->
            try {
                val text = AtomicFile(file).openRead().use { input ->
                    if (file.length() > MAX_BYTES + 32L) return@use ""
                    input.bufferedReader(Charsets.UTF_8).readText()
                }
                val timestamp = text.substringBefore('\n').toLongOrNull() ?: 0L
                if (timestamp <= 0 || now - timestamp !in 0 until MAX_AGE_MS) {
                    AtomicFile(file).delete()
                    emptyList()
                } else text.substringAfter('\n', "").lineSequence().filter { it.isNotBlank() }.toList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
