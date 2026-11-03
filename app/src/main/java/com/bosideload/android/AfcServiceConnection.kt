package com.bosideload.android

import java.io.Closeable
import java.io.IOException
import java.io.StringReader
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter

/**
 * TLS upgrade for a Lockdown StartService endpoint.
 *
 * Apple service TLS presents the paired RootCertificate/RootPrivateKey as
 * the client identity (matching libimobiledevice's Lockdown TLS implementation).
 * The peer certificate is pinned to the paired DeviceCertificate.
 * This performs TLS directly on the newly connected service stream; it does
 * not send a second Lockdown StartSession request.
 */
internal class PairedServiceTlsSocket private constructor(
    private val socket: SSLSocket
) : Closeable {
    val inputStream get() = socket.inputStream
    val outputStream get() = socket.outputStream

    override fun close() = socket.close()

    companion object {
        @Throws(IOException::class)
        fun upgrade(connectSession: UsbMuxConnectSession, record: PairRecord): PairedServiceTlsSocket {
            val transportSocket = StreamBackedSocket(
                connectSession.inputStream,
                connectSession.outputStream
            ) { connectSession.close() }
            return upgrade(transportSocket, record)
        }

        /**
         * Upgrades an already-connected TCP service socket. Used by legacy
         * Apple Wi-Fi Sync after Lockdown StartService returns its service port.
         */
        @Throws(IOException::class)
        fun upgrade(transportSocket: Socket, record: PairRecord): PairedServiceTlsSocket {
            record.validate()
            try {
                val context = createContext(record)
                val ssl = context.socketFactory.createSocket(
                    transportSocket,
                    "paired-apple-service",
                    transportSocket.port,
                    true
                ) as SSLSocket
                ssl.useClientMode = true
                ssl.startHandshake()
                return PairedServiceTlsSocket(ssl)
            } catch (error: Exception) {
                runCatching { transportSocket.close() }
                throw IOException("Paired service TLS handshake failed", error)
            }
        }

        private fun createContext(record: PairRecord): SSLContext {
            val rootCertificate = parseCertificate(record.rootCertificate)
            val hostCertificate = parseCertificate(record.hostCertificate)
            val deviceCertificate = parseCertificate(record.deviceCertificate)
            // Present the paired host identity to the Apple service. The root
            // certificate is the issuer, not the client identity.
            validatePairedServiceCertificates(rootCertificate, hostCertificate, deviceCertificate)
            val hostPrivateKey = parsePrivateKey(record.hostKeyMaterial)
            verifyPairedHostPrivateKeyMatchesCertificate(hostPrivateKey, hostCertificate)
            val rootPrivateKey = parsePrivateKey(record.rootKeyMaterial)
            verifyPairedRootPrivateKeyMatchesCertificate(rootPrivateKey, rootCertificate)
            val identity = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                // libimobiledevice's OpenSSL backend uses the paired RootCertificate
                // and RootPrivateKey as the Apple TLS client identity.
                setKeyEntry(
                    "bo-service-root",
                    rootPrivateKey,
                    CharArray(0),
                    arrayOf(rootCertificate)
                )
            }
            val keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            ).apply { init(identity, CharArray(0)) }.keyManagers
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(deviceCertificate)
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    throw java.security.cert.CertificateException("client trust is not used")
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) {
                        throw java.security.cert.CertificateException("service supplied no peer certificate")
                    }
                    verifyPinnedServicePeerCertificate(chain, deviceCertificate)
                }
            }
            return SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf<TrustManager>(trust), null)
            }
        }

        private fun parseCertificate(bytes: ByteArray): X509Certificate {
            require(bytes.isNotEmpty() && bytes.size <= PairRecordCodec.MAX_FIELD_BYTES)
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(bytes.inputStream()) as X509Certificate
        }

        private fun parsePrivateKey(bytes: ByteArray): PrivateKey {
            require(bytes.isNotEmpty() && bytes.size <= PairRecordCodec.MAX_FIELD_BYTES)
            PEMParser(StringReader(String(bytes, Charsets.US_ASCII))).use { parser ->
                val value = parser.readObject()
                    ?: throw IllegalArgumentException("paired private key is empty")
                val converter = JcaPEMKeyConverter()
                return when (value) {
                    is org.bouncycastle.openssl.PEMKeyPair -> converter.getKeyPair(value).private
                    is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(value)
                    else -> throw IllegalArgumentException("unsupported paired private key PEM")
                }
            }
        }

        private fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Owns a fresh usbmux Connect transport and its optional service TLS layer.
 * Construct this only after StartService succeeded on an authenticated
 * Lockdown channel.
 */
internal class AfcServiceConnection private constructor(
    private val connectSession: UsbMuxConnectSession,
    private val tlsSession: PairedServiceTlsSocket?,
    val client: AfcClient
) : Closeable {
    override fun close() {
        try {
            client.close()
        } finally {
            try {
                tlsSession?.close()
            } finally {
                if (tlsSession == null) connectSession.close()
            }
        }
    }

    companion object {
        const val AFC_SERVICE_IDENTIFIER = "com.apple.afc"

        @Throws(IOException::class)
        fun open(
            transport: UsbMuxTransport,
            deviceId: Long,
            descriptor: LockdownServiceStarter.ServiceDescriptor,
            record: PairRecord
        ): AfcServiceConnection {
            if (descriptor.identifier != AFC_SERVICE_IDENTIFIER) {
                runCatching { transport.close() }
                throw IOException("service descriptor is not com.apple.afc")
            }
            val connect = UsbMuxConnectSession.connect(
                transport = transport,
                deviceId = deviceId,
                devicePort = descriptor.port
            )
            try {
                val tls = if (descriptor.enableServiceSsl) {
                    PairedServiceTlsSocket.upgrade(connect, record)
                } else null
                val input = tls?.inputStream ?: connect.inputStream
                val output = tls?.outputStream ?: connect.outputStream
                val client = AfcClient(input, output)
                return AfcServiceConnection(connect, tls, client)
            } catch (error: Exception) {
                runCatching { connect.close() }
                if (error is IOException) throw error
                throw IOException("Could not initialize AFC service connection", error)
            }
        }
    }
}

/**
 * Confirms that the saved host private key belongs to the paired HostCertificate.
 * Kept internal so the cryptographic check can be covered by local unit tests.
 */
@Throws(IOException::class)
internal fun verifyPairedHostPrivateKeyMatchesCertificate(
    privateKey: PrivateKey,
    certificate: X509Certificate
) {
    try {
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        val signed = signer.sign()

        val verifier = java.security.Signature.getInstance("SHA256withRSA")
        verifier.initVerify(certificate.publicKey)
        verifier.update(challenge)
        if (!verifier.verify(signed)) {
            throw IOException("Paired host private key does not match HostCertificate")
        }
    } catch (error: IOException) {
        throw error
    } catch (error: Exception) {
        throw IOException("Could not validate the paired host key and certificate", error)
    }
}

/**
 * Confirms that the saved pairing root private key belongs to RootCertificate.
 */
@Throws(IOException::class)
internal fun verifyPairedRootPrivateKeyMatchesCertificate(
    privateKey: PrivateKey,
    certificate: X509Certificate
) {
    try {
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        val signed = signer.sign()

        val verifier = java.security.Signature.getInstance("SHA256withRSA")
        verifier.initVerify(certificate.publicKey)
        verifier.update(challenge)
        if (!verifier.verify(signed)) {
            throw IOException("Paired root private key does not match RootCertificate")
        }
    } catch (error: IOException) {
        throw error
    } catch (error: Exception) {
        throw IOException("Could not validate the paired root key and certificate", error)
    }
}

/**
 * Validates the saved Apple pairing certificate chain before TLS is started.
 */
@Throws(IOException::class)
internal fun validatePairedServiceCertificates(
    rootCertificate: X509Certificate,
    hostCertificate: X509Certificate,
    deviceCertificate: X509Certificate
) {
    try {
        if (rootCertificate.subjectX500Principal != rootCertificate.issuerX500Principal) {
            throw IOException("saved root certificate is not self-issued")
        }
        if (hostCertificate.issuerX500Principal != rootCertificate.subjectX500Principal) {
            throw IOException("HostCertificate issuer does not match the saved root")
        }
        if (deviceCertificate.issuerX500Principal != rootCertificate.subjectX500Principal) {
            throw IOException("DeviceCertificate issuer does not match the saved root")
        }
        if (rootCertificate.basicConstraints < 0) {
            throw IOException("saved root certificate is not marked as a certificate authority")
        }
        if (rootCertificate.keyUsage?.getOrNull(5) != true) {
            throw IOException("saved root certificate must allow certificate signing")
        }
        if (hostCertificate.basicConstraints >= 0 || deviceCertificate.basicConstraints >= 0) {
            throw IOException("paired host and device certificates must not be certificate authorities")
        }
        if (hostCertificate.keyUsage?.getOrNull(0) != true ||
            deviceCertificate.keyUsage?.getOrNull(0) != true
        ) {
            throw IOException("paired host and device certificates must allow digital signatures")
        }
        rootCertificate.verify(rootCertificate.publicKey)
        hostCertificate.verify(rootCertificate.publicKey)
        deviceCertificate.verify(rootCertificate.publicKey)
        rootCertificate.checkValidity()
        hostCertificate.checkValidity()
        deviceCertificate.checkValidity()
    } catch (error: Exception) {
        throw IOException("Pair record certificates are not valid under the saved root certificate", error)
    }
}


/**
 * Pins the TLS peer leaf to the exact DeviceCertificate saved during pairing.
 * A matching certificate elsewhere in the chain is not sufficient: otherwise
 * an untrusted leaf could be accepted merely by including the pinned cert as
 * an issuer or unrelated chain element.
 */
@Throws(java.security.cert.CertificateException::class)
internal fun verifyPinnedServicePeerCertificate(
    chain: Array<out X509Certificate>,
    expectedDeviceCertificate: X509Certificate
) {
    if (chain.isEmpty()) {
        throw java.security.cert.CertificateException("service supplied no peer certificate")
    }
    val actualLeaf = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
    val expectedLeaf = MessageDigest.getInstance("SHA-256").digest(expectedDeviceCertificate.encoded)
    if (!MessageDigest.isEqual(actualLeaf, expectedLeaf)) {
        throw java.security.cert.CertificateException(
            "service leaf certificate does not match paired iPhone DeviceCertificate"
        )
    }
    expectedDeviceCertificate.checkValidity()
}
