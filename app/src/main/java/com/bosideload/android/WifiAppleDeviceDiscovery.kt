package com.bosideload.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.io.Closeable
import java.net.InetAddress
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
    private val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("BoSideload-mdns").apply {
        setReferenceCounted(false)
    }
    private val resolvedNames = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var closed = false
    @Volatile private var discoveryStarted = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            discoveryStarted = true
            listener.onStatus("Đang tìm iPhone/iPad bật Wi-Fi Sync trong cùng mạng…")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (closed || serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
            if (!resolvedNames.add(serviceInfo.serviceName)) return
            try {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolvedNames.remove(info.serviceName)
                        if (!closed) listener.onStatus("Không phân giải được dịch vụ mDNS: $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (closed) return
                        val address = info.host?.hostAddress
                        val port = info.port
                        if (address.isNullOrBlank() || port !in 1..65535) {
                            resolvedNames.remove(info.serviceName)
                            return
                        }
                        listener.onDeviceFound(
                            DiscoveredDevice(
                                serviceName = info.serviceName,
                                hostAddress = address,
                                port = port,
                                txtRecord = info.attributes.mapValues { (_, value) -> String(value, Charsets.UTF_8) }
                            )
                        )
                    }
                })
            } catch (_: RuntimeException) {
                resolvedNames.remove(serviceInfo.serviceName)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            resolvedNames.remove(serviceInfo.serviceName)
            if (!closed) listener.onDeviceLost(serviceInfo.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discoveryStarted = false
            if (!closed) listener.onStatus("Đã dừng quét Wi-Fi.")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryStarted = false
            if (multicastLock.isHeld) multicastLock.release()
            if (!closed) listener.onStatus("Không thể bắt đầu quét Bonjour/mDNS (mã $errorCode).")
            runCatching { nsdManager.stopServiceDiscovery(this) }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            runCatching { nsdManager.stopServiceDiscovery(this) }
        }
    }

    @Synchronized
    fun start() {
        check(!closed) { "discovery is closed" }
        if (discoveryStarted) return
        resolvedNames.clear()
        if (!multicastLock.isHeld) multicastLock.acquire()
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (error: RuntimeException) {
            if (multicastLock.isHeld) multicastLock.release()
            listener.onStatus("Không thể quét Bonjour/mDNS: ${error.message ?: "lỗi hệ thống"}")
        }
    }

    @Synchronized
    fun stop() {
        if (discoveryStarted) runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discoveryStarted = false
        if (multicastLock.isHeld) multicastLock.release()
    }

    override fun close() {
        closed = true
        stop()
        resolvedNames.clear()
    }

    companion object {
        const val SERVICE_TYPE = "_apple-mobdev2._tcp."
    }
}
