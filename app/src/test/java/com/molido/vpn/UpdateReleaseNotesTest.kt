package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseNotesTest {
    @Test fun extractsReadableBulletsAndSkipsHeadingsAndLinks() {
        val notes = UpdateSources.releaseNotes(
            """
            ## Molido Mesh AI 2.4
            [critical]
            - Improved automatic route recovery
            * Reduced background data use
            • Fixed DNS leak checks
            - https://example.com/details
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "Improved automatic route recovery",
                "Reduced background data use",
                "Fixed DNS leak checks",
            ),
            notes,
        )
    }

    @Test fun limitsNotesAndLineLength() {
        // Unique entries are required because the production parser intentionally de-duplicates notes.
        val markdown = (1..12).joinToString("\n") { "- item $it " + "x".repeat(260) }
        val notes = UpdateSources.releaseNotes(markdown)
        assertEquals(8, notes.size)
        assertTrue(notes.all { it.length <= 240 })
    }

    @Test fun emptyReleaseBodyHasNoInventedNotes() {
        assertTrue(UpdateSources.releaseNotes("## Release\nNo details supplied").isEmpty())
    }
    @Test fun acceptsOnlyHttpsUrlsWithoutCredentialsOrFragments() {
        assertTrue(UpdateSources.isSecureHttpsUrl("https://updates.example.com/latest.json"))
        assertTrue(!UpdateSources.isSecureHttpsUrl("http://updates.example.com/latest.json"))
        assertTrue(!UpdateSources.isSecureHttpsUrl("file:///tmp/update.apk"))
        assertTrue(!UpdateSources.isSecureHttpsUrl("https://user:pass@updates.example.com/latest.json"))
        assertTrue(!UpdateSources.isSecureHttpsUrl("https://updates.example.com/latest.json#fragment"))
    }

    @Test fun filtersInsecureAndMalformedMirrorUrls() {
        val release = UpdateSources.Release("4.0", "MolidoVPN-android-arm64.apk",
            "http://bad.example/app.apk", null)
        assertEquals(
            listOf("https://good.example/MolidoVPN-android-arm64.apk"),
            UpdateSources.downloadUrls(release, listOf("http://bad.example", "https://good.example", "not a url"))
        )
    }

    @Test fun signerMustBeCurrentOrInVerifiedRotationLineage() {
        val old = byteArrayOf(1, 2, 3)
        val rotated = byteArrayOf(4, 5, 6)
        val unrelated = byteArrayOf(7, 8, 9)

        assertTrue(UpdateSources.isValidSignerTransition(
            listOf(old), false, listOf(old), listOf(old), false
        ))
        assertTrue(UpdateSources.isValidSignerTransition(
            listOf(old), false, listOf(rotated), listOf(old, rotated), false
        ))
        assertTrue(!UpdateSources.isValidSignerTransition(
            listOf(old), false, listOf(rotated), listOf(rotated, unrelated), false
        ))
        assertTrue(!UpdateSources.isValidSignerTransition(
            listOf(old), false, listOf(old, unrelated), listOf(old, unrelated), true
        ))
    }

    @Test fun multiSignerPackagesRequireExactSignerSet() {
        val a = byteArrayOf(1)
        val b = byteArrayOf(2)
        val c = byteArrayOf(3)
        assertTrue(UpdateSources.isValidSignerTransition(listOf(a, b), true, listOf(b, a), listOf(b, a), true))
        assertTrue(!UpdateSources.isValidSignerTransition(listOf(a, b), true, listOf(a, c), listOf(a, c), true))
        assertTrue(!UpdateSources.isValidSignerTransition(listOf(a), false, listOf(a, b), listOf(a, b), true))
    }

}
