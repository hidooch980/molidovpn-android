package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class UpdateSourcesTvTest {
    private fun releaseJson(includeTv: Boolean = true): String {
        val assets = org.json.JSONArray()
            .put(JSONObject().put("name", "MolidoMeshAI-android-arm64.apk")
                .put("browser_download_url", "https://github.com/example/mobile.apk")
                .put("digest", "sha256:" + "a".repeat(64)))
        if (includeTv) {
            assets.put(JSONObject().put("name", "MolidoMeshAI-android-tv-arm64.apk")
                .put("browser_download_url", "https://github.com/example/tv.apk")
                .put("digest", "sha256:" + "b".repeat(64)))
        }
        return JSONObject()
            .put("tag_name", "v4.1.0")
            .put("body", "Android TV edition")
            .put("assets", assets)
            .toString()
    }

    @Test
    fun phoneBuildSelectsThePhoneApk() {
        val release = UpdateSources.parseRelease(releaseJson(), listOf("arm64-v8a"))
        assertEquals("MolidoMeshAI-android-arm64.apk", release?.assetName)
        assertEquals("https://github.com/example/mobile.apk", release?.downloadUrl)
    }

    @Test
    fun televisionBuildSelectsTheTvApk() {
        val release = UpdateSources.parseRelease(
            releaseJson(),
            listOf("arm64-v8a"),
            television = true,
        )
        assertEquals("MolidoMeshAI-android-tv-arm64.apk", release?.assetName)
        assertEquals("https://github.com/example/tv.apk", release?.downloadUrl)
    }

    @Test
    fun televisionBuildDoesNotFallBackToThePhoneApk() {
        assertNull(
            UpdateSources.parseRelease(
                releaseJson(includeTv = false),
                listOf("arm64-v8a"),
                television = true,
            )
        )
    }
}
