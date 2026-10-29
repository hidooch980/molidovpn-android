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
