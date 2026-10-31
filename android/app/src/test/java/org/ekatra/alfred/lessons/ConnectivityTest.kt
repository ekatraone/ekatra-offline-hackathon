package org.ekatra.alfred.lessons

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.cash.turbine.test
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityTest {

    // ---- the tracker: callback sequences ----

    private fun tracker(): Pair<DefaultNetworkTracker, MutableList<Boolean>> {
        val seen = mutableListOf<Boolean>()
        return DefaultNetworkTracker { seen += it } to seen
    }

    @Test
    fun `available then lost ends offline`() {
        val (t, seen) = tracker()
        t.onAvailable("wifi")
        t.onLost("wifi")
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `lost then available again is the reconnect`() {
        val (t, seen) = tracker()
        t.onAvailable("wifi"); t.onLost("wifi"); t.onAvailable("wifi")
        assertEquals(listOf(true, false, true), seen)
    }

    @Test
    fun `a handover to mobile data does not flash offline when the old network is lost afterwards`() {
        val (t, seen) = tracker()
        t.onAvailable("wifi")
        t.onAvailable("cell")   // the default network moved
        t.onLost("wifi")        // the old one is lost after
        assertEquals("still online", true, seen.last())
        assertFalse(seen.drop(1).contains(false))
    }

    @Test
    fun `losing the current network after a handover goes offline`() {
        val (t, seen) = tracker()
        t.onAvailable("wifi"); t.onAvailable("cell"); t.onLost("wifi"); t.onLost("cell")
        assertEquals(false, seen.last())
    }

    @Test
    fun `capabilities decide whether the current network has internet`() {
        val (t, seen) = tracker()
        t.onAvailable("wifi")
        t.onCapabilitiesChanged("wifi", hasInternet = false)
        t.onCapabilitiesChanged("wifi", hasInternet = true)
        assertEquals(listOf(true, false, true), seen)
    }

    @Test
    fun `capabilities of a network that is not the default are ignored`() {
        val (t, seen) = tracker()
        t.onAvailable("wifi")
        t.onCapabilitiesChanged("cell", hasInternet = false)
        assertEquals(listOf(true), seen)
    }

    @Test
    fun `a loss with nothing tracked still reports offline`() {
        val (t, seen) = tracker()
        t.onLost("wifi")
        assertEquals(listOf(false), seen)
    }

    // ---- the real class, with a fake ConnectivityManager that has the stale-activeNetwork behaviour ----

    private class Rig {
        val network = mockk<Network>(relaxed = true)
        val other = mockk<Network>(relaxed = true)
        val caps = mockk<NetworkCapabilities>()
        val cm = mockk<ConnectivityManager>()
        val callback = slot<ConnectivityManager.NetworkCallback>()
        val context = mockk<Context>()
        var activeNetwork: Network? = null

        init {
            every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
            every { cm.activeNetwork } answers { activeNetwork }
            every { cm.getNetworkCapabilities(any()) } returns caps
            every { cm.registerDefaultNetworkCallback(capture(callback)) } just runs
            every { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just runs
        }
    }

    @Test
    fun `airplane mode reports offline on onLost even though activeNetwork is stale, and isOnline agrees`() = runTest {
        val rig = Rig()
        rig.activeNetwork = rig.network
        val connectivity = AndroidConnectivity(rig.context)

        connectivity.onlineFlow().test {
            assertEquals(true, awaitItem())                 // online at start
            // Airplane mode: the default network is lost, but activeNetwork STILL returns it at that instant.
            rig.callback.captured.onLost(rig.network)
            assertEquals(false, awaitItem())
            assertFalse("isOnline must not trust the stale activeNetwork", connectivity.isOnline())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline to online emits true again so the reconnect sync fires`() = runTest {
        val rig = Rig()
        rig.activeNetwork = null
        val connectivity = AndroidConnectivity(rig.context)

        connectivity.onlineFlow().test {
            assertEquals(false, awaitItem())                // starts offline
            rig.callback.captured.onAvailable(rig.network)
            assertEquals(true, awaitItem())
            // isOnline agrees immediately, even if activeNetwork has not caught up yet.
            assertTrue(connectivity.isOnline())
            rig.callback.captured.onLost(rig.network)
            assertEquals(false, awaitItem())
            rig.callback.captured.onAvailable(rig.other)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `it registers the default-network callback and unregisters it when collection stops`() = runTest {
        val rig = Rig()
        val connectivity = AndroidConnectivity(rig.context)

        connectivity.onlineFlow().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 1) { rig.cm.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        verify(exactly = 1) { rig.cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        // With nobody collecting, isOnline falls back to asking the system.
        rig.activeNetwork = rig.network
        assertTrue(connectivity.isOnline())
    }

    @Test
    fun `repeated identical states are not re-emitted`() = runTest {
        val rig = Rig()
        rig.activeNetwork = rig.network
        val connectivity = AndroidConnectivity(rig.context)

        connectivity.onlineFlow().test {
            assertEquals(true, awaitItem())
            rig.callback.captured.onAvailable(rig.network)
            rig.callback.captured.onCapabilitiesChanged(rig.network, rig.caps)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
