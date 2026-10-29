package com.molido.vpn

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SubscriptionSnapshotsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun line(id: Int) = "trojan://fixture$id@192.0.2.$id:443?sni=tls.example"

    @Test fun quickPartialRefreshKeepsOtherSourcesAndSuccessfulReplacementDropsOldEntries() {
        val cache = SubscriptionSnapshots(temp.newFolder())
        cache.replace("slow", line(1), 1000)
        cache.replace("fast", line(2), 1000)
        cache.replace("fast", line(3), 1100)
        cache.replace("fast", line(2), 1050) // cancelled prior request completed late
        val merged = cache.read(listOf("fast", "slow"), 1200)
        assertEquals(setOf(line(1), line(3)), merged.toSet())
        assertEquals(2, V2rayNodes.parse(merged).size)
        cache.replace("slow", "", 1300) // failed/empty fetch must not erase its usable snapshot
        assertTrue(cache.read(listOf("fast", "slow"), 1400).contains(line(1)))
    }

    @Test fun staleAndFutureSnapshotsDoNotRemainCandidates() {
        val cache = SubscriptionSnapshots(temp.newFolder())
        cache.replace("source", line(1), 1000)
        assertEquals(listOf(line(1)), cache.read(listOf("source"), 1000))
        assertTrue(cache.read(listOf("source"), 1000 + SubscriptionSnapshots.MAX_AGE_MS).isEmpty())
        cache.replace("source", line(1), 2000)
        assertTrue(cache.read(listOf("source"), 1999).isEmpty())
    }

    @Test fun removedSourceIsPrunedAndOversizeCannotReplaceUsableSnapshot() {
        val folder = temp.newFolder()
        val cache = SubscriptionSnapshots(folder)
        cache.replace("active", line(1), 1000)
        cache.replace("removed", line(2), 1000)
        cache.replace("active", "x".repeat(2_000_001), 1100)
        assertEquals(listOf(line(1)), cache.read(listOf("active"), 1200))
        assertEquals(1, folder.listFiles()!!.size)
        assertEquals(listOf(line(1)), cache.read(listOf("active", "removed"), 1300))
    }

    @Test fun aggregateCachePublishesWholeGenerationAndRecoversBackup() {
        val file = File(temp.newFolder(), "catalog.txt")
        SubscriptionSnapshots.writeAtomically(file, line(1))
        assertEquals(line(1), SubscriptionSnapshots.readAtomically(file))
        // Simulate a legacy interrupted replacement: AtomicFile restores .bak.
        assertTrue(file.renameTo(File(file.path + ".bak")))
        file.writeText("partial")
        assertEquals(line(1), SubscriptionSnapshots.readAtomically(file))
        SubscriptionSnapshots.writeAtomically(file, line(2))
        assertEquals(line(2), SubscriptionSnapshots.readAtomically(file))
    }

    @Test fun unchangedPollsOnlyRenewDiskSnapshotOncePerMinute() {
        val folder = temp.newFolder()
        val cache = SubscriptionSnapshots(folder)
        cache.replace("source", line(1), 1000)
        cache.replace("source", line(1), 2000)
        assertEquals("1000", SubscriptionSnapshots.readAtomically(folder.listFiles()!!.single()).substringBefore('\n'))
        cache.replace("source", line(1), 61_000)
        assertEquals("61000", SubscriptionSnapshots.readAtomically(folder.listFiles()!!.single()).substringBefore('\n'))
    }
}
