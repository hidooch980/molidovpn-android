package com.molido.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ResilienceConfigTest {

    private val key = "a".repeat(64)

    @Test
    fun parsesAndValidates() {
        val c = ResilienceConfig.parse(
            """{"mirrors":{"update":["https://dl.example.ir/molido/","http://insecure.example.com","https://u:p@evil.com/x","ftp://x"],
               "sub":["https://cdn.example.com/sub.txt","not a url"]},
               "dns_tunnel":{"enabled":true,"domain":"T.Example.com.","pubkey":"${key.uppercase()}",
               "udp":["178.22.122.100","10.202.10.10:5353","999.1.1.1","1.2.3.4:0","x"],
               "doh":["https://dns.google/dns-query","http://plain/dns"],"dot":["1.1.1.1"],"in_auto":false}}"""
        )
        assertEquals(listOf("https://dl.example.ir/molido"), c.updateMirrors)
        assertEquals(listOf("https://cdn.example.com/sub.txt"), c.subMirrors)
        val d = c.dnsTunnel
        assertEquals("t.example.com", d.domain)
        assertEquals(key, d.pubkey)
        assertEquals(listOf("178.22.122.100:53", "10.202.10.10:5353"), d.udpResolvers)
        assertEquals(listOf("https://dns.google/dns-query"), d.dohUrls)
        assertEquals(listOf("1.1.1.1:853"), d.dotAddrs)
        assertFalse(d.inAuto)
        assertTrue(d.usable)
    }

    @Test
    fun emptyAndBadInputIsSafe() {
        val empty = ResilienceConfig.parse("{}")
        assertEquals(ResilienceConfig.Config(), empty)
        assertFalse(empty.dnsTunnel.usable)
        // Enabled but no key: not usable.
        assertFalse(ResilienceConfig.parse("""{"dns_tunnel":{"enabled":true,"domain":"t.example.com","pubkey":"zz"}}""").dnsTunnel.usable)
    }

    @Test
    fun mergeKeepsBundledMirrorsAndPrefersRemoteTunnel() {
        val bundled = ResilienceConfig.Config(updateMirrors = listOf("https://b.example.com"),
            dnsTunnel = ResilienceConfig.DnsTunnelSettings(udpResolvers = listOf("1.1.1.1:53")))
        val remote = ResilienceConfig.Config(updateMirrors = listOf("https://r.example.com", "https://b.example.com"))
        val m = ResilienceConfig.merge(remote, bundled)
        assertEquals(listOf("https://r.example.com", "https://b.example.com"), m.updateMirrors)
        assertEquals(bundled.dnsTunnel, m.dnsTunnel) // remote said nothing about the tunnel
        assertEquals(bundled, ResilienceConfig.merge(null, bundled))
    }

    @Test
    fun sourceUrlsIncludeMirrors() {
        val urls = ResilienceConfig.sourceUrls(listOf("https://m.example.com"))
        assertEquals(ResilienceConfig.SOURCE_URLS.first(), urls.first())
        assertEquals("https://m.example.com/remote/resilience.json", urls.last())
    }

    @Test
    fun shippedFilesParseAndAreOffByDefault() {
        for (path in listOf("../remote/resilience.json", "src/main/assets/resilience-defaults.json")) {
            val c = ResilienceConfig.parse(File(path).readText())
            assertFalse(path, c.dnsTunnel.enabled)
            assertTrue(path, c.dnsTunnel.udpResolvers.isNotEmpty())
        }
        assertEquals(File("../remote/resilience.json").readText(), File("src/main/assets/resilience-defaults.json").readText())
    }
}

class OfflineConfigsTest {

    @Test
    fun upsertKeepsNewestPerModeAndCounts() {
        var list = emptyList<OfflineConfigs.Entry>()
        for (i in 1..5) list = OfflineConfigs.upsert(list, "v2ray", "vless://n$i", now = 1000L + i, perMode = 3)
        assertEquals(listOf("vless://n5", "vless://n4", "vless://n3"), list.map { it.uri })
        list = OfflineConfigs.upsert(list, "v2ray", "vless://n3", now = 2000L, perMode = 3)
        assertEquals("vless://n3", list.first().uri)
        assertEquals(2, list.first().okCount)
        list = OfflineConfigs.upsert(list, "shard", "vless://s1", now = 2001L, perMode = 3)
        assertEquals(4, list.size) // other modes have their own quota
    }

    @Test
    fun staleEntriesAreDropped() {
        val list = listOf(OfflineConfigs.Entry("v2ray", "vless://old", 0L, 1), OfflineConfigs.Entry("v2ray", "vless://new", 900L, 1))
        assertEquals(listOf("vless://new"), OfflineConfigs.trim(list, now = 1000L, maxAgeMs = 500L).map { it.uri })
    }

    @Test
    fun encodeDecodeRoundTripAndGarbage() {
        val list = listOf(OfflineConfigs.Entry("v2ray", "trojan://x@h:443#A%20B", 5L, 3))
        assertEquals(list, OfflineConfigs.decode(OfflineConfigs.encode(list)))
        assertTrue(OfflineConfigs.decode("not json").isEmpty())
        assertTrue(OfflineConfigs.decode("""[{"u":"no-scheme"}]""").isEmpty())
    }

    @Test
    fun emergencyLinesSkipCommentsAndJunk() {
        val text = "# header\n\nvless://a@b:443\n  trojan://c@d:443#x  \nhello\n"
        assertEquals(listOf("vless://a@b:443", "trojan://c@d:443#x"), OfflineConfigs.emergencyLines(text))
    }

    @Test
    fun bundledEmergencyFileIsReadable() {
        // Placeholder in git; filled by tools/refresh-emergency-configs.py in release builds.
        OfflineConfigs.emergencyLines(File("src/main/assets/emergency-configs.txt").readText())
    }
}

class UpdateSourcesTest {

    private val sha = "c76d0d163b30dcd3bf0f623823e255d82067139541f1c43975a30ef2815d1b1c"

    private val github = """{"tag_name":"v3.0.126","assets":[
        {"name":"changelog.json","browser_download_url":"https://x/changelog.json"},
        {"name":"MolidoVPN-android-arm64.apk","browser_download_url":"https://gh/arm64.apk","digest":"sha256:$sha"},
        {"name":"MolidoVPN-android-universal.apk","browser_download_url":"https://gh/uni.apk"}]}"""

    @Test
    fun pickAbiAndDigest() {
        val r = UpdateSources.parseRelease(github, listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("3.0.126", r.version)
        assertEquals("MolidoVPN-android-arm64.apk", r.assetName)
        assertEquals(sha, r.sha256)
        // armv7 phone: no armv7 asset → universal, no hash listed.
        val u = UpdateSources.parseRelease(github, listOf("armeabi-v7a"))!!
        assertEquals("MolidoVPN-android-universal.apk", u.assetName)
        assertNull(u.sha256)
    }

    @Test
    fun sha256FieldFromMirrorAndInvalidOnesIgnored() {
        assertEquals(sha, UpdateSources.assetSha256(JSONObject().put("sha256", sha.uppercase())))
        assertNull(UpdateSources.assetSha256(JSONObject().put("digest", "md5:abc")))
        assertNull(UpdateSources.assetSha256(JSONObject().put("sha256", "123")))
    }

    @Test
    fun sourceOrder() {
        val m = listOf("https://m1.example.com/", "https://m2.example.com")
        assertEquals(
            listOf(UpdateSources.WORKER_RELEASE_URL, UpdateSources.GITHUB_RELEASE_URL,
                "https://m1.example.com/latest.json", "https://m2.example.com/latest.json"),
            UpdateSources.releaseJsonUrls(m),
        )
        assertEquals(listOf(UpdateSources.WORKER_RELEASE_URL, UpdateSources.GITHUB_RELEASE_URL), UpdateSources.releaseJsonUrls(emptyList()))
        val r = UpdateSources.Release("1", "A.apk", "https://w/app/A.apk", null)
        assertEquals(listOf("https://w/app/A.apk", "https://m1.example.com/A.apk", "https://m2.example.com/A.apk"),
            UpdateSources.downloadUrls(r, m))
    }

    @Test
    fun hashAndSigners() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateSources.sha256Hex(ByteArrayInputStream("abc".toByteArray())))
        val a = "cert-a".toByteArray(); val b = "cert-b".toByteArray()
        assertTrue(UpdateSources.sameSigners(listOf(a), listOf("cert-a".toByteArray())))
        assertFalse(UpdateSources.sameSigners(listOf(a), listOf(b)))
        assertFalse(UpdateSources.sameSigners(emptyList(), listOf(b)))
    }
}

class DnsTunnelTest {

    private val settings = ResilienceConfig.DnsTunnelSettings(
        enabled = true, domain = "t.example.com", pubkey = "b".repeat(64),
        udpResolvers = listOf("178.22.122.100:53", "192.168.1.1:53"),
        dohUrls = listOf("https://dns.google/dns-query"),
    )

    @Test
    fun candidateOrderEncryptedThenSystemThenDomestic() {
        val c = DnsTunnel.candidates(settings, listOf("192.168.1.1", "fe80::1"))
        assertEquals(
            listOf(
                DnsTunnel.Candidate(DnsTunnel.Kind.DOH, "https://dns.google/dns-query"),
                DnsTunnel.Candidate(DnsTunnel.Kind.UDP, "192.168.1.1:53"),
                DnsTunnel.Candidate(DnsTunnel.Kind.UDP, "178.22.122.100:53"),
            ),
            c,
        )
        assertEquals(3, DnsTunnel.candidates(settings.copy(useSystemDns = false), listOf("10.0.0.1")).size)
    }

    @Test
    fun clientCommandLine() {
        assertEquals(
            listOf("-udp", "178.22.122.100:53", "-pubkey", "b".repeat(64), "t.example.com", "127.0.0.1:1826"),
            DnsTunnel.clientArgs(DnsTunnel.Candidate(DnsTunnel.Kind.UDP, "178.22.122.100:53"), settings),
        )
    }
}
