package com.molido.vpn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ShardStopRecoveryTest {
    private class DelayedProcess : Process() {
        val waiting = CountDownLatch(1)
        val finish = CountDownLatch(1)
        @Volatile var stopper: Thread? = null
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun destroy() {}
        override fun isAlive() = true
        override fun exitValue() = 0
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            stopper = Thread.currentThread()
            waiting.countDown()
            return finish.await(timeout, unit)
        }
    }

    private class TrackedProcess : Process() {
        @Volatile var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun destroy() { destroyed = true }
        override fun isAlive() = !destroyed
        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun waitFor(): Int { destroyed = true; return 0 }
    }

    @Test fun cancellingSeamlessTrialDestroysDetachedCandidateAndInvalidatesGeneration() {
        val candidateField = ShardManager::class.java.getDeclaredField("seamlessCandidate")
            .apply { isAccessible = true }
        val generationField = ShardManager::class.java.getDeclaredField("switchGeneration")
            .apply { isAccessible = true }
        val generation = generationField.get(ShardManager) as java.util.concurrent.atomic.AtomicLong
        val candidate = TrackedProcess()
        val before = generation.get()
        try {
            candidateField.set(ShardManager, candidate)
            ShardManager.cancelSeamlessSwitch()
            assertTrue("detached candidate must be terminated immediately", candidate.destroyed)
            assertNull("cancelled candidate must no longer be promotable", candidateField.get(ShardManager))
            assertTrue("generation must invalidate an in-flight trial", generation.get() > before)
        } finally {
            candidateField.set(ShardManager, null)
            candidate.destroy()
        }
    }

    @Test fun delayedOldStopCannotClearReplacementSession() {
        val process = ShardManager::class.java.getDeclaredField("process").apply { isAccessible = true }
        val running = ShardManager::class.java.getDeclaredField("running").apply { isAccessible = true }
            .get(ShardManager) as AtomicBoolean
        val old = DelayedProcess()
        val replacement = DelayedProcess()
        try {
            process.set(ShardManager, old)
            running.set(true)
            ShardManager.stop()
            assertTrue(old.waiting.await(2, TimeUnit.SECONDS))
            // Equivalent publication point to a new start(), on its real monitor.
            synchronized(ShardManager) {
                process.set(ShardManager, replacement)
                running.set(true)
            }
            old.finish.countDown()
            old.stopper!!.join(2_000)
            assertFalse(old.stopper!!.isAlive)
            assertSame(replacement, process.get(ShardManager))
            assertTrue("Old cleanup must not mark the fresh session stopped", ShardManager.isRunning)
        } finally {
            old.finish.countDown()
            old.stopper?.join(2_000)
            process.set(ShardManager, null)
            running.set(false)
        }
    }
}
