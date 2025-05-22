// File: app/src/main/java/com/example/projectnoodle/HttpsWebServer.kt
package com.example.projectnoodle

import android.content.Context
import android.net.Uri
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

private const val TAG = "ProjectNoodleHttpsServer"
private const val KEYSTORE_PASSWORD = ""
private const val CERT_VALIDITY_DAYS = 365
private const val KEY_ALG = "RSA"
private const val SIG_ALG = "SHA256withRSA" // Standard JCA name

class HttpsWebServer(
    port: Int,
    applicationContext: Context,
    sharedDirectoryUri: Uri,
    serverIpAddress: String?,
    requireApprovalEnabled: Boolean,
    approvalListener: ConnectionApprovalListener?
) : WebServer(
    port,
    applicationContext,
    sharedDirectoryUri,
    serverIpAddress,
    requireApprovalEnabled,
    approvalListener
) {

    // Bouncy Castle provider registration is done in MainActivity.onCreate()
    // using Security.insertProviderAt(BouncyCastleProvider(), 1)
    init {
        Log.d(TAG, "HttpsWebServer: Initialized with port $port. Attempting to set up HTTPS.")

        try {
            val sslSocketFactory = createSSLServerSocketFactory(serverIpAddress)
            makeSecure(sslSocketFactory, null)
            Log.d(TAG, "HttpsWebServer: HTTPS setup complete. Server will listen for secure connections.")
        } catch (e: Exception) {
            Log.e(TAG, "HttpsWebServer: Failed to set up HTTPS: ${e.message}", e)
            Log.e(TAG, "HttpsWebServer: Full stack trace for HTTPS setup failure:", e)
            throw IOException("Failed to initialize HTTPS server.", e)
        }
    }

    private fun createSSLServerSocketFactory(serverIpAddress: String?): javax.net.ssl.SSLServerSocketFactory {
        val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALG)
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        Log.d(TAG, "Generated RSA KeyPair.")

        val cert: X509Certificate = generateSelfSignedCertificate(keyPair, serverIpAddress)
        Log.d(TAG, "Generated self-signed X.509 Certificate.")

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, KEYSTORE_PASSWORD.toCharArray())
        keyStore.setKeyEntry("server_alias", keyPair.private, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))
        Log.d(TAG, "KeyStore created and populated with server key and certificate.")

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD.toCharArray())
        Log.d(TAG, "KeyManagerFactory initialized.")

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, null)
        Log.d(TAG, "SSLContext initialized with server key and certificate.")

        return sslContext.serverSocketFactory
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair, ipAddress: String?): X509Certificate {
        val commonName = ipAddress ?: "localhost"
        val distinguishedName = "CN=$commonName, O=Project Noodle, OU=Self-Signed"
        val issuer = X500Name(distinguishedName)
        val subject = X500Name(distinguishedName)

        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        val notAfter = Date(System.currentTimeMillis() + CERT_VALIDITY_DAYS * 24L * 60 * 60 * 1000)

        // Rely on the BouncyCastleProvider being inserted at position 1 in MainActivity.
        // JcaContentSignerBuilder will internally call Signature.getInstance(SIG_ALG),
        // which should now find the BC implementation due to its high priority.
        val contentSigner: ContentSigner = JcaContentSignerBuilder(SIG_ALG)
            .build(keyPair.private)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )

        val certHolder: X509CertificateHolder = certBuilder.build(contentSigner)

        val cert: X509Certificate = JcaX509CertificateConverter()
            .getCertificate(certHolder)

        cert.checkValidity(Date())
        cert.verify(keyPair.public)

        Log.d(TAG, "Self-signed certificate generated for CN: $commonName, valid until: ${cert.notAfter}")
        return cert
    }
}
