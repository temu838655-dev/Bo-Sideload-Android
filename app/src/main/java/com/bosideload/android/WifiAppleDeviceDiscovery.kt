package com.bosideload.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers legacy Apple Wi-Fi Sync advertisements on the local network.
 *
 * Discovery is deliberately not authentication: callers must verify the
 * advertised identity against an existing encrypted pairing record before
 * opening any Apple service socket.
 */
internal class WifiAppleDeviceDiscovery(
    context: Context,
    private val listener: Listener
) : Closeable {
    interface Listener {
        fun onStatus(message: String)
        fun onDeviceFound(device: DiscoveredDevice)
        fun onDeviceLost(serviceName: String)
    }

    data class DiscoveredDevice(
        val serviceName: String,
        val hostAddress: String,
        val port: Int,
        val txtRecord: Map<String, String>
    )

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("BoSideload-mdns").apply {
        setReferenceCounted(false)
    }

    // The token identifies one specific resolve request. A service name may
    // disappear and reappear while an older resolve callback is still queued.
    private val resolvingTokens = ConcurrentHashMap<String, Any>()
    @Volatile private var closed = false
    @Volatile private var discoveryStarted = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            discoveryStarted = true
            listener.onStatus("Đang tìm iPhone/iPad bật Wi-Fi Sync trong cùng mạng…")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (closed || serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
            val token = Any()
            if (resolvingTokens.putIfAbsent(serviceInfo.serviceName, token) != null) return
            try {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        val isCurrentAttempt = resolvingTokens.remove(info.serviceName, token)
                        if (!closed && isCurrentAttempt) {
                            listener.onStatus("Không phân giải được dịch vụ mDNS: $errorCode")
                        }
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        // Ignore a callback unless it still belongs to the current
                        // resolve attempt for this service name.
                        if (closed || resolvingTokens[info.serviceName] !== token) return
                        val address = info.host?.hostAddress
                        val port = info.port
                        if (address.isNullOrBlank() || port !in 1..65535) {
                            resolvingTokens.remove(info.serviceName, token)
                            return
                        }
                        listener.onDeviceFound(
                            DiscoveredDevice(
                                serviceName = info.serviceName,
                                hostAddress = address,
                                port = port,
                                txtRecord = info.attributes.mapValues { (_, value) ->
                                    String(value, Charsets.UTF_8)
                                }
                            )
                        )
                    }
                })
            } catch (_: RuntimeException) {
                resolvingTokens.remove(serviceInfo.serviceName, token)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            resolvingTokens.remove(serviceInfo.serviceName)
            if (!closed) listener.onDeviceLost(serviceInfo.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discoveryStarted = false
            releaseMulticastLock()
            resolvingTokens.clear()
            if (!closed) listener.onStatus("Đã dừng quét Wi-Fi.")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryStarted = false
            releaseMulticastLock()
            resolvingTokens.clear()
            if (!closed) listener.onStatus("Không thể bắt đầu quét Bonjour/mDNS (mã $errorCode).")
            runCatching { nsdManager.stopServiceDiscovery(this) }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            // NSD may fail to stop asynchronously; do not retain the Wi-Fi multicast lock.
            discoveryStarted = false
            releaseMulticastLock()
            runCatching { nsdManager.stopServiceDiscovery(this) }
            if (!closed) listener.onStatus("Không dừng được quét mDNS sạch sẽ (mã $errorCode); đã nhả khóa Wi-Fi.")
        }
    }

    @Synchronized
    fun start() {
        check(!closed) { "discovery is closed" }
        if (discoveryStarted) return
        resolvingTokens.clear()
        if (!multicastLock.isHeld) multicastLock.acquire()
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (error: RuntimeException) {
            releaseMulticastLock()
            listener.onStatus("Không thể quét Bonjour/mDNS: ${error.message ?: "lỗi hệ thống"}")
        }
    }

    private fun releaseMulticastLock() {
        if (multicastLock.isHeld) runCatching { multicastLock.release() }
    }

    @Synchronized
    fun stop() {
        if (discoveryStarted) runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discoveryStarted = false
        resolvingTokens.clear()
        releaseMulticastLock()
    }

    override fun close() {
        closed = true
        stop()
    }

    companion object {
        const val SERVICE_TYPE = "_apple-mobdev2._tcp."
    }
}
