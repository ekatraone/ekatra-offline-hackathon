package org.ekatra.alfred.lessons

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

interface Connectivity {
    fun isOnline(): Boolean

    /** Emits the current state, then every change (a default-network callback under the hood). */
    fun onlineFlow(): Flow<Boolean>
}

/**
 * Turns default-network callbacks into online/offline changes.
 *
 * It never asks ConnectivityManager what is active at the moment of a loss: at `onLost`, `activeNetwork`
 * can still return the network that just dropped, which made the old code report "online" forever after
 * airplane mode went on. Here the loss itself means offline.
 *
 * A network lost while another has already become the default (Wi-Fi to mobile data) is ignored, so a
 * handover does not flash "offline".
 */
internal class DefaultNetworkTracker(private val onChange: (Boolean) -> Unit) {
    private var current: Any? = null

    @Synchronized
    fun onAvailable(network: Any) {
        current = network
        onChange(true)
    }

    @Synchronized
    fun onCapabilitiesChanged(network: Any, hasInternet: Boolean) {
        if (current != null && current != network) return
        current = network
        onChange(hasInternet)
    }

    @Synchronized
    fun onLost(network: Any) {
        if (current == null || current == network) {
            current = null
            onChange(false)
        }
    }
}

@Singleton
class AndroidConnectivity @Inject constructor(
    @ApplicationContext private val context: Context
) : Connectivity {

    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The last state the callback reported, while someone is collecting [onlineFlow]; null otherwise. */
    @Volatile private var tracked: Boolean? = null

    // Same answer as the flow, so a sync started by a reconnect event never sees a stale "offline".
    override fun isOnline(): Boolean = tracked ?: queryOnline()

    private fun queryOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onlineFlow(): Flow<Boolean> = callbackFlow {
        val tracker = DefaultNetworkTracker { online ->
            tracked = online
            trySend(online)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = tracker.onAvailable(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                tracker.onCapabilitiesChanged(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            override fun onLost(network: Network) = tracker.onLost(network)
        }
        val initial = queryOnline()
        tracked = initial
        trySend(initial)
        // The default network, not "any network with INTERNET": its loss is exactly "we are offline".
        cm.registerDefaultNetworkCallback(callback)
        awaitClose {
            tracked = null
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
