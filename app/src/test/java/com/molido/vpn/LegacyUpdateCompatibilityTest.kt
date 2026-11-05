package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for installs that predate the 4.x release line.
 *
 * These tests cover release discovery only. The Android PackageManager must still
 * verify versionCode and the real APK signing lineage before installation.
 */
class LegacyUpdateCompatibilityTest {
    @Test
    fun legacy32161And32162RecognizeCurrentStableReleaseAsNewer() {
        assertTrue(UpdateSources.isNewerVersion("4.0.2", "3.2.161"))
        assertTrue(UpdateSources.isNewerVersion("4.0.3", "3.2.161"))
        assertTrue(UpdateSources.isNewerVersion("4.0.2", "3.2.162"))
        assertTrue(UpdateSources.isNewerVersion("4.0.3", "3.2.162"))
        assertTrue(!UpdateSources.isNewerVersion("3.2.162", "4.0.2"))
        assertTrue(UpdateSources.isNewerVersion("4.0.2", "3.3.60"))
        assertTrue(!UpdateSources.isNewerVersion("3.2.161", "4.0.2"))
    }

    @Test
    fun currentGithubReleaseAssetNamesAreRecognizedForLegacyPhoneInstall() {
        val digest = "fd65ec8e36546ebbcc564080ab380cd0d5ef2a19570c96f5a4652f4b81f3591b"
        val json = """
            {
              "tag_name": "v4.0.2",
              "body": "- Improved update reliability",
              "assets": [
                {
                  "name": "MolidoMeshAI-android-arm64.apk",
                  "browser_download_url": "https://github.com/hidooch980/molidovpn-android/releases/download/v4.0.2/MolidoMeshAI-android-arm64.apk",
                  "digest": "sha256:$digest"
                },
                {
                  "name": "MolidoMeshAI-android-armv7.apk",
                  "browser_download_url": "https://github.com/hidooch980/molidovpn-android/releases/download/v4.0.2/MolidoMeshAI-android-armv7.apk",
                  "digest": "sha256:f05e297848c5fbdf6a7664fe02d622f8dfc56879bd35c62b015194ca735a0fad"
                },
                {
                  "name": "MolidoMeshAI-android-universal.apk",
                  "browser_download_url": "https://github.com/hidooch980/molidovpn-android/releases/download/v4.0.2/MolidoMeshAI-android-universal.apk",
                  "digest": "sha256:78ce32d3085354978d32cf0d7e41a2899f400fbe5a67a2fda24100eef111fc50"
                }
              ]
            }
        """.trimIndent()

        val release = UpdateSources.parseRelease(json, listOf("arm64-v8a"))
        assertNotNull(release)
        assertEquals("4.0.2", release?.version)
        assertEquals("MolidoMeshAI-android-arm64.apk", release?.assetName)
        assertEquals(digest, release?.sha256)
        assertTrue(UpdateSources.isNewerVersion(release!!.version, "3.2.161"))
        assertTrue(UpdateSources.isNewerVersion(release.version, "3.2.162"))
    }

    @Test
    fun legacyUpgradeMustStillRejectMissingDigestOrInsecureAssetUrl() {
        val json = """
            {
              "tag_name": "v4.0.2",
              "assets": [
                {
                  "name": "MolidoMeshAI-android-arm64.apk",
                  "browser_download_url": "http://github.com/example/app.apk"
                }
              ]
            }
        """.trimIndent()

        assertEquals(null, UpdateSources.parseRelease(json, listOf("arm64-v8a")))
    }
}
