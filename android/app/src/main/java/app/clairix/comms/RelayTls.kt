package app.clairix.comms

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * What the relay connection trusts.
 *
 * First the platform's own verdict: its trust store, which the app's network
 * security configuration limits to the system's certificate authorities, so
 * a CA someone installed on the phone counts for nothing. Then, when the
 * owner has asked for it on the Certificates page, the key the TLS canary
 * pinned: a chain whose leaf key is not the pinned one is refused even when
 * a trusted CA signed it. That is exactly the certificate a coerced or
 * compelled CA would produce, and the one case the trust store alone cannot
 * catch.
 *
 * The pin is read on every handshake, so forgetting it on the Certificates
 * page (after the relay rotates its key) takes effect at once, with no
 * restart.
 */
object RelayTls {

    /** "sha256/<base64>" of a certificate's SubjectPublicKeyInfo: the form the canary pins. */
    fun spkiHash(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return "sha256/" + Base64.getEncoder().encodeToString(digest)
    }

    /** The platform's default trust manager, under the app's network security configuration. */
    fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No X509 trust manager on this platform")
    }

    /** [builder] with the pinning trust manager installed over the platform's. */
    fun pinned(builder: OkHttpClient.Builder, pin: () -> String?): OkHttpClient.Builder {
        val manager = PinningTrustManager(platformTrustManager(), pin)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return builder.sslSocketFactory(context.socketFactory, manager)
    }
}

/**
 * Accepts a server chain only when [delegate] does and, if [pin] names a key,
 * the leaf's key is that one. Every client this is installed in talks to the
 * relay alone, so the pin needs no host to go with it.
 */
class PinningTrustManager(
    private val delegate: X509TrustManager,
    private val pin: () -> String?,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkServerTrusted(chain, authType)
        val expected = pin() ?: return
        val leaf = chain.firstOrNull() ?: throw CertificateException("The relay presented no certificate")
        val seen = RelayTls.spkiHash(leaf)
        if (seen != expected) {
            throw CertificateException(
                "The relay's certificate key is not the pinned one ($seen). If the relay rotated its key, " +
                    "forget the pin on the Certificates page; otherwise something is answering in its place."
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
