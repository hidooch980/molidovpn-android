package com.molido.vpn

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LiveCatalogBudgetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("settings", 0).edit().clear().commit() }

    @Test fun fiveSecondTicksAndOwnerRestartsDoNotBypassTheRemoteAttemptFloor() {
        assertTrue(LiveCatalogSync.claimRefresh(context, 1000))
        // Same persisted setting is used by activity and service owners, including failed fetches.
        for (elapsed in 5_000L until LiveCatalogSync.REMOTE_INTERVAL_MS step 5_000L)
            assertFalse(LiveCatalogSync.claimRefresh(context.applicationContext, 1000 + elapsed))
        assertTrue(LiveCatalogSync.claimRefresh(context, 1000 + LiveCatalogSync.REMOTE_INTERVAL_MS))
        assertFalse(LiveCatalogSync.claimRefresh(context, 1001 + LiveCatalogSync.REMOTE_INTERVAL_MS))
    }

    @Test fun backwardClockDoesNotDisableUpdatesIndefinitely() {
        assertTrue(LiveCatalogSync.claimRefresh(context, 500_000))
        assertTrue(LiveCatalogSync.claimRefresh(context, 1000))
        assertFalse(LiveCatalogSync.claimRefresh(context, 2000))
    }
}
