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


    @Test fun releaseParserSelectsArm64ForV400AndRequiresThePublishedDigest() {
        val hash = "b".repeat(64)
        val json = """{
            "tag_name":"v4.0.0",
            "assets":[
                {"name":"MolidoMeshAI-android-universal.apk","browser_download_url":"https://cdn.example/universal.apk","digest":"sha256:$hash"},
                {"name":"MolidoMeshAI-android-arm64.apk","browser_download_url":"https://cdn.example/arm64.apk","digest":"sha256:$hash"},
                {"name":"MolidoMeshAI-android-armv7.apk","browser_download_url":"https://cdn.example/armv7.apk","digest":"sha256:$hash"}
            ]
        }"""
        val release = UpdateSources.parseRelease(json, listOf("arm64-v8a"))
        assertEquals("4.0.0", release?.version)
        assertEquals("MolidoMeshAI-android-arm64.apk", release?.assetName)
        assertEquals(hash, release?.sha256)
    }


    private fun release(version: String) = UpdateSources.Release(
        version = version,
        assetName = "MolidoMeshAI-android-arm64.apk",
        downloadUrl = "https://updates.example/$version.apk",
        sha256 = null,
    )

    private fun isNewer(remote: String, installed: String): Boolean {
        val remoteParts = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val installedParts = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(remoteParts.size, installedParts.size)) {
            val comparison = (remoteParts.getOrElse(index) { 0 })
                .compareTo(installedParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    @Test fun staleStaticCopyDoesNotBlockWorkerRelease() {
        val staticUrl = LiveSync.RAW + "latest.json"
        val calls = mutableListOf<String>()
        val result = UpdateSources.latest(
            listOf(staticUrl, UpdateSources.WORKER_RELEASE_URL),
            "3.3.60",
            ::isNewer,
        ) { url ->
            calls += url
            when (url) {
                staticUrl -> release("3.3.60")
                UpdateSources.GITHUB_RELEASE_URL -> null
                UpdateSources.WORKER_RELEASE_URL -> release("4.0.0")
                else -> null
            }
        }
        assertEquals("4.0.0", result?.version)
        assertTrue(calls.contains(UpdateSources.WORKER_RELEASE_URL))
    }

    @Test fun githubNewerThanStaleStaticReleaseWins() {
        val staticUrl = LiveSync.RAW + "latest.json"
        val result = UpdateSources.latest(
            listOf(staticUrl, UpdateSources.WORKER_RELEASE_URL),
            "3.2.161",
            ::isNewer,
        ) { url ->
            when (url) {
                staticUrl -> release("3.3.60")
                UpdateSources.GITHUB_RELEASE_URL -> release("4.0.0")
                UpdateSources.WORKER_RELEASE_URL -> release("4.0.0")
                else -> null
            }
        }
        assertEquals("4.0.0", result?.version)
    }

    @Test fun failedGithubFreshnessCheckDoesNotBlockWorkerRelease() {
        val staticUrl = LiveSync.RAW + "latest.json"
        val result = UpdateSources.latest(
            listOf(staticUrl, UpdateSources.WORKER_RELEASE_URL),
            "3.3.60",
            ::isNewer,
        ) { url ->
            when (url) {
                staticUrl -> null
                UpdateSources.GITHUB_RELEASE_URL -> throw IllegalStateException("GitHub unavailable")
                UpdateSources.WORKER_RELEASE_URL -> release("4.0.0")
                else -> null
            }
        }
        assertEquals("4.0.0", result?.version)
    }

    @Test fun workerCanSupersedeStaticCopyWhenGithubIsUnavailable() {
        val staticUrl = LiveSync.RAW + "latest.json"
        val result = UpdateSources.latest(
            listOf(staticUrl, UpdateSources.WORKER_RELEASE_URL),
            "3.2.161",
            ::isNewer,
        ) { url ->
            when (url) {
                staticUrl -> release("3.3.60")
                UpdateSources.GITHUB_RELEASE_URL -> throw IllegalStateException("GitHub unavailable")
                UpdateSources.WORKER_RELEASE_URL -> release("4.0.0")
                else -> null
            }
        }
        assertEquals("4.0.0", result?.version)
    }

    @Test fun allReachableSourcesWithoutANewerReleaseReturnNull() {
        val staticUrl = LiveSync.RAW + "latest.json"
        val result = UpdateSources.latest(
            listOf(staticUrl, UpdateSources.WORKER_RELEASE_URL),
            "4.0.0",
            ::isNewer,
        ) { null }
        assertEquals(null, result)
    }



}
