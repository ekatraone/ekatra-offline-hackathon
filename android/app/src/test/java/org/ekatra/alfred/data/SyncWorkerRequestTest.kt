package org.ekatra.alfred.data

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import org.ekatra.alfred.data.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerRequestTest {

    @Test
    fun `learner sync waits for a network and retries on a short linear backoff`() {
        val spec = SyncWorker.learnerSyncRequest().workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
        // A cold-start failure retries in 10 s, not minutes: "Synced" must show within seconds.
        assertEquals(10_000L, spec.backoffDelayDuration)
    }

    @Test
    fun `reconnect replaces a queued run and everything else keeps it`() {
        assertEquals(ExistingWorkPolicy.REPLACE, SyncWorker.learnerSyncPolicy(replace = true))
        assertEquals(ExistingWorkPolicy.KEEP, SyncWorker.learnerSyncPolicy(replace = false))
    }
}
