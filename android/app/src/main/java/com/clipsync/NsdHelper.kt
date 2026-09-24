package com.clipsync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Wraps Android's NsdManager to discover the ClipSync Mac daemon on the local network.
 *
 * How mDNS/DNS-SD discovery works:
 * 1. The Mac daemon registers itself as "_clipsync._tcp." on the local network
 * 2. This helper listens for that service type using multicast DNS
 * 3. When found, it resolves the service to get the actual IP address and port
 * 4. The callback notifies ClipSyncService to connect via TCP
 *
 * MulticastLock: On Android 12 and below, the Wi-Fi driver may filter out
 * multicast packets to save battery. We acquire a MulticastLock to ensure
 * mDNS packets are delivered to our app.
 */
class NsdHelper(
    private val context: Context,
    private val callback: NsdCallback
) {
    companion object {
        private const val TAG = "NsdHelper"
        /** The mDNS service type registered by the Mac daemon */
        private const val SERVICE_TYPE = "_clipsync._tcp."
    }

    /** Callback interface for service discovery events */
    interface NsdCallback {
        /** Called when a ClipSync service is found and resolved to an IP:port */
        fun onServiceResolved(serviceName: String, host: String, port: Int)
        /** Called when a previously discovered service disappears from the network */
        fun onServiceLost(serviceName: String)
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * MulticastLock ensures mDNS multicast packets reach our app.
     * Without this, Android's Wi-Fi driver may drop multicast traffic
     * as a battery optimization (especially on Android 12 and below).
     */
    private val multicastLock: WifiManager.MulticastLock =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("ClipSync_mDNS_lock")

    @Volatile
    private var isDiscovering = false

    /**
     * Services waiting to be resolved. Before API 34, NsdManager handles only one
     * resolve at a time: reusing a listener that is still in use throws
     * IllegalArgumentException, and a concurrent resolve fails with
     * FAILURE_ALREADY_ACTIVE. So resolves are queued and run one after another.
     * Guarded by [resolveLock].
     */
    private val pendingResolves = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val resolveLock = Any()

    /**
     * NSD discovery listener — receives callbacks when services are found/lost.
     * 
     * Note: These callbacks run on an internal NSD thread, NOT the main thread.
     * We only use them to trigger further actions (resolve, callback).
     */
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "Discovery started for: $serviceType")
            isDiscovering = true
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped for: $serviceType")
            isDiscovering = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
            // Resolve the service to get its IP address and port
            // We resolve any service of our type — the Mac daemon will be the only one
            enqueueResolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.w(TAG, "Service lost: ${serviceInfo.serviceName}")
            callback.onServiceLost(serviceInfo.serviceName)
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed: errorCode=$errorCode")
            isDiscovering = false
            // Could retry here, but let the service handle reconnection logic
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop discovery failed: errorCode=$errorCode")
            isDiscovering = false
        }
    }

    /**
     * NSD resolve listener — converts a service name into an actual IP:port.
     *
     * Resolution involves querying the network for the service's SRV and A/AAAA records.
     * This gives us the concrete address we need to open a TCP connection.
     *
     * A new listener is created per resolve; when it finishes, the next queued
     * service (if any) is resolved.
     */
    private inner class ResolveListener : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Resolve failed: ${serviceInfo.serviceName}, errorCode=$errorCode")
            resolveFinished()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress
            val port = serviceInfo.port
            Log.i(TAG, "Service resolved: $host:$port (${serviceInfo.serviceName})")

            if (host != null && port > 0) {
                callback.onServiceResolved(serviceInfo.serviceName, host, port)
            } else {
                Log.e(TAG, "Resolved service has invalid host=$host or port=$port")
            }
            resolveFinished()
        }
    }

    /** Queue [serviceInfo] for resolution, skipping duplicates already queued. */
    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        synchronized(resolveLock) {
            if (pendingResolves.none { it.serviceName == serviceInfo.serviceName }) {
                pendingResolves.addLast(serviceInfo)
            }
            if (!isResolving) resolveNextLocked()
        }
    }

    private fun resolveFinished() {
        synchronized(resolveLock) {
            isResolving = false
            resolveNextLocked()
        }
    }

    /** Start resolving the next queued service. Caller must hold [resolveLock]. */
    @Suppress("DEPRECATION") // resolveService is replaced by registerServiceInfoCallback on API 34+
    private fun resolveNextLocked() {
        while (pendingResolves.isNotEmpty()) {
            val next = pendingResolves.removeFirst()
            try {
                nsdManager.resolveService(next, ResolveListener())
                isResolving = true
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve ${next.serviceName}", e)
            }
        }
    }

    /**
     * Start discovering ClipSync services on the local network.
     * 
     * Acquires the multicast lock first to ensure mDNS packets are received,
     * then begins NSD discovery for _clipsync._tcp. services.
     */
    fun startDiscovery() {
        Log.d(TAG, "Starting mDNS discovery...")

        // Acquire multicast lock for mDNS on Android 12 and below
        if (!multicastLock.isHeld) {
            multicastLock.setReferenceCounted(false)
            multicastLock.acquire()
            Log.d(TAG, "MulticastLock acquired")
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    /**
     * Stop discovering services and release resources.
     * Called when the service is destroyed.
     */
    fun stopDiscovery() {
        Log.d(TAG, "Stopping discovery...")

        if (isDiscovering) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop discovery", e)
            }
        }

        synchronized(resolveLock) {
            pendingResolves.clear()
        }

        // Release the multicast lock to restore battery optimization
        if (multicastLock.isHeld) {
            multicastLock.release()
            Log.d(TAG, "MulticastLock released")
        }
    }
}
