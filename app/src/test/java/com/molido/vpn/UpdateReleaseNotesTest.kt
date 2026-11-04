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

    @Test fun releaseMetadataSizeIsBoundedEvenWithoutContentLength() {
        val max = UpdateSources.MAX_METADATA_BYTES
        assertTrue(UpdateSources.withinMetadataSizeLimit(max, max))
        assertTrue(!UpdateSources.withinMetadataSizeLimit(max + 1, 0))
        assertTrue(UpdateSources.withinMetadataSizeLimit(-1, max))
        assertTrue(!UpdateSources.withinMetadataSizeLimit(-1, max + 1))
    }

    @Test fun apkDownloadSizeIsBoundedEvenWithoutContentLength() {
        val max = UpdateSources.MAX_APK_BYTES
        assertTrue(UpdateSources.withinApkSizeLimit(max, max))
        assertTrue(!UpdateSources.withinApkSizeLimit(max + 1, 0))
        assertTrue(UpdateSources.withinApkSizeLimit(-1, max))
        assertTrue(!UpdateSources.withinApkSizeLimit(-1, max + 1))
    }

    @Test fun multiSignerPackagesRequireExactSignerSet() {
        val a = byteArrayOf(1)
        val b = byteArrayOf(2)
        val c = byteArrayOf(3)
        assertTrue(UpdateSources.isValidSignerTransition(listOf(a, b), true, listOf(b, a), listOf(b, a), true))
        assertTrue(!UpdateSources.isValidSignerTransition(listOf(a, b), true, listOf(a, c), listOf(a, c), true))
        assertTrue(!UpdateSources.isValidSignerTransition(listOf(a), false, listOf(a, b), listOf(a, b), true))
    }


    @Test fun releaseParserRejectsApksWithoutValidSha256() {
        val hash = "a".repeat(64)
        val json = """{
            "tag_name":"3.1.99",
            "assets":[
                {"name":"MolidoVPN-android-arm64.apk","browser_download_url":"https://cdn.example/arm64.apk"},
                {"name":"MolidoVPN-android-universal.apk","browser_download_url":"https://cdn.example/universal.apk","digest":"sha256:$hash"}
            ]
        }"""
        val release = UpdateSources.parseRelease(json, listOf("arm64-v8a"))
        assertEquals("MolidoVPN-android-universal.apk", release?.assetName)
        assertEquals(hash, release?.sha256)
    }

    @Test fun releaseParserRejectsMalformedSha256() {
        val json = """{
            "tag_name":"3.1.100",
            "assets":[
                {"name":"MolidoVPN-android-arm64.apk","browser_download_url":"https://cdn.example/arm64.apk","sha256":"not-a-hash"}
            ]
        }"""
        assertEquals(null, UpdateSources.parseRelease(json, listOf("arm64-v8a")))
    }


}
