package com.bosideload.android

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.nio.charset.StandardCharsets

/**
 * Starts an authenticated Lockdown session and upgrades the existing usbmux
 * byte stream to TLS using the saved host identity.
 *
 * The server certificate is pinned to the DeviceCertificate from the accepted
 * PairRecord. No permissive trust-all manager or pairing bypass is used.
 */
internal class LockdownTlsSession private constructor(
    val sessionId: String,
    private val sslSocket: SSLSocket,
    val channel: LockdownSessionChannel
) : Closeable {
    override fun close() {
        runCatching { channel.close() }
        runCatching { sslSocket.close() }
    }

    companion object {
        @Throws(IOException::class)
        fun start(
            plainChannel: LockdownSessionChannel,
            transportSocket: Socket,
            record: PairRecord
        ): LockdownTlsSession {
            record.validate()
            val response = plainChannel.exchange(
                LockdownRequests.startSession(record.hostId, record.systemBuid)
            )
            val success = response as? LockdownResponseParser.Response.Success
                ?: throw IOException("Lockdown rejected StartSession")
            if (success.request != "StartSession") {
                throw IOException("unexpected Lockdown response to StartSession")
            }
            if (success.enableSessionSsl != true) {
                throw IOException("Lockdown did not enable session TLS")
            }
            val sessionId = success.sessionId?.takeIf { it.isNotBlank() }
                ?: throw IOException("Lockdown StartSession response has no SessionID")

            try {
                val context = createSslContext(record)
                val factory = context.socketFactory
                val ssl = factory.createSocket(
                    transportSocket,
                    "lockdown-device",
                    62078,
                    true
                ) as SSLSocket
                ssl.useClientMode = true
                ssl.startHandshake()
                return LockdownTlsSession(
                    sessionId = sessionId,
                    sslSocket = ssl,
                    channel = LockdownSessionChannel(ssl.inputStream, ssl.outputStream)
                )
            } catch (error: Exception) {
                runCatching { transportSocket.close() }
                throw IOException("Lockdown TLS handshake failed", error)
            }
        }

        private fun createSslContext(record: PairRecord): SSLContext {
            val hostCertificate = parseCertificate(record.hostCertificate)
            val deviceCertificate = parseCertificate(record.deviceCertificate)
            val hostPrivateKey = parsePrivateKey(record.hostKeyMaterial)

            val identityStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry(
                    "bo-lockdown-host",
                    hostPrivateKey,
                    CharArray(0),
                    arrayOf(hostCertificate)
                )
            }
            val keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            ).apply { init(identityStore, CharArray(0)) }.keyManagers

            val pinnedTrustManager = PinnedDeviceTrustManager(deviceCertificate)
            return SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf<TrustManager>(pinnedTrustManager), null)
            }
        }

        private fun parseCertificate(pem: ByteArray): X509Certificate {
            require(pem.isNotEmpty() && pem.size <= PairRecordCodec.MAX_FIELD_BYTES)
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(pem.inputStream()) as X509Certificate
        }

        private fun parsePrivateKey(pem: ByteArray): PrivateKey {
            require(pem.isNotEmpty() && pem.size <= PairRecordCodec.MAX_FIELD_BYTES)
            val text = String(pem, StandardCharsets.US_ASCII)
            PEMParser(StringReader(text)).use { parser ->
                val obj = parser.readObject()
                    ?: throw IllegalArgumentException("host private key PEM is empty")
                val converter = JcaPEMKeyConverter()
                val keyPair = when (obj) {
                    is org.bouncycastle.openssl.PEMKeyPair -> converter.getKeyPair(obj)
                    is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(obj)
                    else -> throw IllegalArgumentException("unsupported host private key PEM")
                }
                return if (keyPair is PrivateKey) keyPair else keyPair.private
            }
        }
    }

    private class PinnedDeviceTrustManager(
        private val expected: X509Certificate
    ) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(expected)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw java.security.cert.CertificateException("client certificate validation is not used")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw java.security.cert.CertificateException("Lockdown server supplied no certificate")
            }
            val expectedFingerprint = fingerprint(expected)
            val matches = chain.any { fingerprint(it) == expectedFingerprint }
            if (!matches) {
                throw java.security.cert.CertificateException("Lockdown server certificate does not match the paired device")
            }
            expected.checkValidity()
        }

        private fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Adapts the USBMux raw service streams to the Socket API required by Android's
 * SSLSocketFactory layered-socket overload. Socket close owns the USB stream.
 */
internal class StreamBackedSocket(
    private val source: InputStream,
    private val sink: OutputStream,
    private val closeAction: () -> Unit
) : Socket() {
    @Volatile private var isClosed = false

    override fun getInputStream(): InputStream {
        check(!isClosed) { "socket is closed" }
        return source
    }

    override fun getOutputStream(): OutputStream {
        check(!isClosed) { "socket is closed" }
        return sink
    }

    override fun isConnected(): Boolean = !isClosed
    override fun isClosed(): Boolean = isClosed

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { source.close() }
        runCatching { sink.close() }
        closeAction()
    }
}
