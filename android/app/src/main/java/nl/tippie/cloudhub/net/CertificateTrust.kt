package nl.tippie.cloudhub.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A certificate the device does not trust is a question, not a decision.
 *
 * Self-hosted CloudHub instances commonly sit behind a private CA or a
 * self-signed certificate. The tempting shortcut -- trust everything -- turns
 * every install into a silent man-in-the-middle, so instead:
 *
 *   1. the platform trust manager gets first refusal, so a properly issued
 *      certificate, or a private CA the user installed on the device, works
 *      with no prompt at all;
 *   2. failing that, the leaf certificate's SHA-256 is checked against what
 *      the user has previously accepted for this connection;
 *   3. failing that it throws [UntrustedCertificate], which carries the
 *      fingerprint so the UI can show it and offer to pin it.
 *
 * Pinning is per certificate, not per host: "yes once" must not become "trust
 * anything this host ever presents".
 */
class UntrustedCertificate(
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val reason: String,
) : CertificateException("Untrusted certificate: $fingerprint")

interface PinnedCertificates {
    fun isPinned(fingerprint: String): Boolean
}

class PinningTrustManager(
    private val pins: PinnedCertificates,
) : X509TrustManager {

    private val platform: X509TrustManager = run {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            platform.checkServerTrusted(chain, authType)
            return
        } catch (platformRefused: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw platformRefused
            val fingerprint = fingerprintOf(leaf)
            if (pins.isPinned(fingerprint)) return
            throw UntrustedCertificate(
                fingerprint = fingerprint,
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                reason = platformRefused.message ?: "not signed by a trusted authority",
            )
        }
    }

    // A client certificate is never presented, so this delegates unchanged.
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        platform.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    companion object {
        /** Uppercase colon-separated SHA-256, the form certificate tools print. */
        fun fingerprintOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }

        fun sslSocketFactory(trustManager: X509TrustManager) =
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), java.security.SecureRandom())
            }.socketFactory
    }
}
