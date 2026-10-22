package app.clairix.nav

import android.util.Log
import app.clairix.BuildConfig
import uniffi.clairix_comms_crypto.verify as ed25519Verify

/**
 * The Clairix asset signing key, built into the app (see tools/signing).
 * Road graphs, work-zone files and release checksums are signed with its
 * secret half before they are published, and nothing downloaded is used
 * until its signature checks: a checksum from the same place as the file
 * only proves the download arrived intact, not that it is what was
 * published. A release build cannot be made without the public key; a debug
 * build made without it skips the check and says so in the log.
 */
object AssetSignature {

    private const val TAG = "AssetSignature"

    /** The public key, raw 32 bytes in base64, as `android/asset-signing.pub` holds it; empty when the build has none. */
    val publicKey: String = BuildConfig.ASSET_PUBKEY.trim()

    val enforced: Boolean = publicKey.isNotEmpty()

    /** Whether [signatureB64] (one line, as a `.sig` file holds it) is the key's signature over [message]. */
    fun verify(message: ByteArray, signatureB64: String): Boolean {
        if (!enforced) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "This build has no asset signing key; a download was not verified")
                return true
            }
            return false
        }
        val signature = signatureB64.trim()
        if (signature.isEmpty() || signature.length > 128) return false
        return runCatching { ed25519Verify(publicKey, message, signature) }.getOrDefault(false)
    }
}
