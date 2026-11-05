package app.clairix.analysis

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.clairix.HealthFact
import app.clairix.Severity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Phone check's hardware boot check: Android key attestation, read on the phone.
 *
 * The boot rows above it in Phone check ([PhoneHealthMonitor]) come from system properties
 * (ro.boot.verifiedbootstate and the like), which the running system reports about itself; a
 * system that was tampered with can report anything. Key attestation asks the phone's secure
 * hardware instead: Clairix makes a throwaway key in the Android Keystore with a random challenge,
 * and the Trusted Execution Environment (or StrongBox, a separate security chip, when the phone has
 * one) issues a certificate for it that records, among other things, whether the bootloader was
 * locked and what verified boot found when the phone started (the KeyDescription extension,
 * OID [KEY_DESCRIPTION_OID]). That certificate is signed by a key the hardware was given at the
 * factory, whose own certificate leads up a chain to a root.
 *
 * What Clairix checks: the challenge in the record is the one it just made (so the answer is fresh
 * and for this check), each certificate in the chain is signed by the next, and the last one signs
 * itself. What it does not check: whether that root is one of Google's published attestation roots,
 * and whether any certificate in the chain is on Google's revocation list. Both need the internet,
 * and Clairix makes no network request for this. So a phone whose secure hardware was replaced or
 * emulated (root with a module that fakes attestation, or a factory key that leaked and was revoked)
 * can give the same clean answer, and the row says so. It is a second opinion on the rows above,
 * strongest when it disagrees with them; never proof that a phone is clean. The root's SHA-256 is
 * shown, so someone with Google's list can compare it by hand.
 *
 * The key is deleted as soon as the certificates are read. Only certificates (public data) are
 * read; the private half never leaves the hardware and is never used. It has nothing to do with the
 * key that signs evidence reports ([KeystoreReportSigner]) or with any key that signs Clairix itself.
 *
 * [Der] reads the record; [parseKeyDescription], [assess] and [fact] are plain Kotlin, tested on the
 * JVM with certificates built in the test. [read] asks the phone, once per process ([cached]).
 */
internal object KeyAttestation {

    /** The X.509 extension Android's key attestation record is carried in. */
    const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"

    /** Throwaway aliases start with this, so one left behind by a crash is found and deleted. */
    private const val ALIAS_PREFIX = "clairix_boot_check_"
    private const val PROVIDER = "AndroidKeyStore"

    /** AuthorizationList tags ([n] EXPLICIT) this reads. */
    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_OS_PATCH_LEVEL = 706

    /** Who vouches for the record: the security level of the keystore that wrote it. */
    enum class SecurityLevel(val label: String) {
        SOFTWARE("software"),
        TRUSTED_ENVIRONMENT("the Trusted Execution Environment"),
        STRONG_BOX("StrongBox, a separate security chip");

        companion object {
            fun of(v: Long): SecurityLevel = when (v) {
                0L -> SOFTWARE
                1L -> TRUSTED_ENVIRONMENT
                2L -> STRONG_BOX
                else -> throw DerException("unknown security level $v")
            }
        }
    }

    /** What verified boot found, as the hardware records it. */
    enum class BootState {
        /** Booted an OS signed with the key built into the phone (green). */
        VERIFIED,
        /** Booted an OS signed with a key the owner installed (yellow). */
        SELF_SIGNED,
        /** Booted without verifying the OS (orange: an unlocked bootloader). */
        UNVERIFIED,
        /** Verification failed (red). */
        FAILED;

        companion object {
            fun of(v: Long): BootState = entries.getOrNull(v.toInt().takeIf { v in 0L..3L } ?: -1)
                ?: throw DerException("unknown verified boot state $v")
        }
    }

    /** The RootOfTrust: how the phone booted, by the hardware's account. */
    data class RootOfTrust(val deviceLocked: Boolean, val bootState: BootState, val verifiedBootKeySha256: String)

    /** The parts of the KeyDescription this check reads. */
    data class KeyDescription(
        val attestationVersion: Long,
        val attestationSecurityLevel: SecurityLevel,
        val challenge: ByteArray,
        /** From the hardware-enforced list; null when the hardware gave none. */
        val rootOfTrust: RootOfTrust?,
        /** The OS security patch level the hardware recorded, as yyyymm; null when absent. */
        val osPatchLevel: Long?,
    )

    /** What one attestation showed, judged by [assess]. */
    data class Result(
        val description: KeyDescription,
        /** The challenge in the record is the one Clairix made for this check. */
        val challengeMatches: Boolean,
        val chainLength: Int,
        /** The first certificate (0 is the key's own) not signed by the one after it; null when every link holds. */
        val brokenLink: Int?,
        /** The last certificate verifies with its own public key. */
        val rootSelfSigned: Boolean,
        /** SHA-256 of the last certificate, colon-separated hex, for comparing with Google's published roots by hand. */
        val rootSha256: String,
        /** StrongBox made the key (else the TEE, or whatever the keystore is). */
        val strongBox: Boolean,
    ) {
        /** The record is fresh, the chain links up, and the hardware (not software) wrote it. */
        val checksOut: Boolean
            get() = challengeMatches && brokenLink == null && rootSelfSigned &&
                description.attestationSecurityLevel != SecurityLevel.SOFTWARE && description.rootOfTrust != null
    }

    /** The answer for Phone check: a [Result], or why there is none. */
    sealed interface State {
        data class Read(val result: Result) : State
        data class Unavailable(val reason: String) : State
    }

    /**
     * Reads a KeyDescription from the extension value [extension] (X509Certificate.getExtensionValue:
     * an OCTET STRING whose content is the KeyDescription SEQUENCE). Throws [DerException] on
     * anything malformed.
     */
    fun parseKeyDescription(extension: ByteArray): KeyDescription {
        val wrapped = Der.parse(extension)
        if (!wrapped.isUniversal(Der.TAG_OCTET_STRING)) throw DerException("extension value is not an OCTET STRING")
        val seq = Der.parse(wrapped.octets())
        if (!seq.isUniversal(Der.TAG_SEQUENCE)) throw DerException("KeyDescription is not a SEQUENCE")
        val f = seq.children()
        if (f.size < 8) throw DerException("KeyDescription has ${f.size} fields, not 8")
        val hardware = authorizations(f[7])
        return KeyDescription(
            attestationVersion = f[0].long(),
            attestationSecurityLevel = SecurityLevel.of(f[1].long()),
            challenge = f[4].octets(),
            rootOfTrust = hardware[TAG_ROOT_OF_TRUST]?.let(::rootOfTrust),
            osPatchLevel = hardware[TAG_OS_PATCH_LEVEL]?.long(),
        )
    }

    /** An AuthorizationList: a SEQUENCE of [n] EXPLICIT entries, by tag number. */
    private fun authorizations(list: Der.Element): Map<Int, Der.Element> {
        if (!list.isUniversal(Der.TAG_SEQUENCE)) throw DerException("AuthorizationList is not a SEQUENCE")
        val out = HashMap<Int, Der.Element>()
        for (e in list.children()) {
            if (e.tagClass != Der.CLASS_CONTEXT) throw DerException("AuthorizationList entry is not context-tagged")
            if (out.put(e.tag, e.explicit()) != null) throw DerException("AuthorizationList repeats tag ${e.tag}")
        }
        return out
    }

    private fun rootOfTrust(e: Der.Element): RootOfTrust {
        if (!e.isUniversal(Der.TAG_SEQUENCE)) throw DerException("RootOfTrust is not a SEQUENCE")
        val f = e.children()
        if (f.size < 3) throw DerException("RootOfTrust has ${f.size} fields")
        return RootOfTrust(
            deviceLocked = f[1].bool(),
            bootState = BootState.of(f[2].long()),
            verifiedBootKeySha256 = ReportSigning.sha256Hex(f[0].octets()),
        )
    }

    /**
     * Index of the first certificate in [chain] that is not signed by the one after it, or null
     * when every link holds.
     */
    fun brokenLink(chain: List<X509Certificate>): Int? =
        (0 until chain.size - 1).firstOrNull { i -> runCatching { chain[i].verify(chain[i + 1].publicKey) }.isFailure }

    /** Judges [chain] (the key's certificate first) against the [challenge] Clairix made. Throws [DerException] on a malformed record. */
    fun assess(chain: List<X509Certificate>, challenge: ByteArray, strongBox: Boolean): Result {
        if (chain.isEmpty()) throw DerException("no certificates")
        val ext = chain[0].getExtensionValue(KEY_DESCRIPTION_OID) ?: throw DerException("no attestation record in the certificate")
        val description = parseKeyDescription(ext)
        val root = chain.last()
        return Result(
            description = description,
            challengeMatches = MessageDigest.isEqual(description.challenge, challenge),
            chainLength = chain.size,
            brokenLink = brokenLink(chain),
            rootSelfSigned = runCatching { root.verify(root.publicKey) }.isSuccess,
            rootSha256 = AppEvidence.sha256(root.encoded),
            strongBox = strongBox,
        )
    }

    // ── The Phone check row ────────────────────────────────────────────────

    private const val ID = "attestation"
    private const val CATEGORY = "Integrity"
    private const val LABEL = "Hardware boot check"

    /** What every answer says it does and does not prove. */
    internal const val LIMITS = "Clairix checked that the answer was made for this check and that each certificate is signed " +
        "by the next. It did not compare the last certificate with Google's list of attestation roots or check Google's " +
        "revocation list, which both need the internet, so a phone whose secure hardware was faked or whose factory key " +
        "leaked could give the same answer. A second opinion on the rows above, not proof. The throwaway key was deleted."

    /**
     * The Phone check row for [state], set beside what the boot properties say: [propsUnlocked] true
     * when they report an unlocked bootloader, false when locked, null when they do not say.
     */
    fun fact(state: State, propsUnlocked: Boolean?): HealthFact = when (state) {
        is State.Unavailable -> HealthFact(
            ID, CATEGORY, LABEL, "Not available",
            detail = "The phone's secure hardware did not give an attestation (${state.reason}). Many older or budget " +
                "phones cannot; the rows above are then all Clairix has."
        )
        is State.Read -> readFact(state.result, propsUnlocked)
    }

    private fun readFact(r: Result, propsUnlocked: Boolean?): HealthFact {
        val d = r.description
        val trust = d.rootOfTrust
        val tail = " Signed in ${d.attestationSecurityLevel.label}; ${r.chainLength} certificates; root certificate " +
            "SHA-256 ${r.rootSha256}.${d.osPatchLevel?.let { " Security patch the hardware recorded: ${patch(it)}." }.orEmpty()} $LIMITS"
        return when {
            !r.challengeMatches || r.brokenLink != null || !r.rootSelfSigned -> HealthFact(
                ID, CATEGORY, LABEL, "Did not check out", Severity.MEDIUM,
                (if (!r.challengeMatches) "The answer was not made for this check." else "The certificates do not link up to a root.") +
                    " Something between Clairix and the secure hardware may have changed it.$tail"
            )
            d.attestationSecurityLevel == SecurityLevel.SOFTWARE || trust == null -> HealthFact(
                ID, CATEGORY, LABEL, "Software only",
                detail = "Only software answered, not secure hardware, so the answer says nothing about how the phone booted.$tail"
            )
            else -> bootFact(trust, propsUnlocked, tail)
        }
    }

    private fun bootFact(t: RootOfTrust, propsUnlocked: Boolean?, tail: String): HealthFact {
        val unlocked = !t.deviceLocked || t.bootState == BootState.UNVERIFIED
        // The properties say locked while the hardware says unlocked: the running system is not
        // telling the truth about itself, which is worth more than either answer alone.
        val disagree = propsUnlocked == false && unlocked
        val also = if (disagree) " The boot rows above say the opposite: the running system reports a locked phone the hardware does not." else ""
        return when {
            t.bootState == BootState.FAILED -> HealthFact(
                ID, CATEGORY, LABEL, "Verification FAILED", Severity.CRITICAL,
                "The secure hardware records that verified boot failed.$also$tail"
            )
            unlocked -> HealthFact(
                ID, CATEGORY, LABEL, if (disagree) "UNLOCKED (disagrees)" else "UNLOCKED", Severity.HIGH,
                "The secure hardware records an unlocked bootloader${if (t.bootState == BootState.UNVERIFIED) " and an unverified OS" else ""}: " +
                    "anyone with the phone in hand could have put a modified system on it.$also$tail"
            )
            t.bootState == BootState.SELF_SIGNED -> HealthFact(
                ID, CATEGORY, LABEL, "Locked, custom OS key", Severity.MEDIUM,
                "The bootloader is locked to an OS signed with a key that is not the maker's. Expected on a custom OS you " +
                    "installed (GrapheneOS, CalyxOS); otherwise a warning sign.$tail"
            )
            else -> HealthFact(
                ID, CATEGORY, LABEL, "Locked, verified",
                detail = "The secure hardware records a locked bootloader and an OS signed with the key built into the phone.$tail"
            )
        }
    }

    /** "2026-10" from 202610. */
    private fun patch(yyyymm: Long): String = "%04d-%02d".format(java.util.Locale.ROOT, yyyymm / 100, yyyymm % 100)

    // ── On the phone ───────────────────────────────────────────────────────

    /** The answer for this process: the boot state cannot change until the phone restarts. */
    @Volatile
    private var answer: State? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * This process's answer, or null while it is still being worked out. The first call starts
     * [read] on a thread of its own, since making a key in StrongBox can take a second or two and
     * Phone check runs on the scanner's loop, and calls [onReady] when the answer is in, so the
     * caller can publish it.
     */
    fun current(context: Context, onReady: () -> Unit): State? {
        answer?.let { return it }
        if (started.compareAndSet(false, true)) {
            val app = context.applicationContext
            Thread({
                answer = runCatching { read(app) }.getOrElse { State.Unavailable(reason(it)) }
                runCatching(onReady)
            }, "clairix-boot-check").apply { isDaemon = true }.start()
        }
        return null
    }

    /** The row while [current] is still working. */
    fun checking(): HealthFact = HealthFact(
        ID, CATEGORY, LABEL, "Checking",
        detail = "Clairix is asking the phone's secure hardware how it booted; the answer shows here in a moment."
    )

    /**
     * Makes a throwaway attestation key (StrongBox when the phone has it, else the TEE), reads its
     * certificate chain, deletes the key and judges the chain. Keystore work that can take a second
     * or two with StrongBox; call off the main thread.
     */
    fun read(context: Context): State {
        val ks = KeyStore.getInstance(PROVIDER).also { it.load(null) }
        // A key left behind by a check the process did not live to finish.
        runCatching { ks.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }.forEach(ks::deleteEntry) }
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val alias = ALIAS_PREFIX + ReportSigning.sha256Hex(challenge).take(16)
        val wantStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            // StrongBox when the phone has it; StrongBoxUnavailableException, or any other refusal,
            // falls back to the TEE.
            val strongBox = wantStrongBox && runCatching { generate(alias, challenge, strongBox = true) }
                .onFailure { runCatching { ks.deleteEntry(alias) } }
                .isSuccess
            if (!strongBox) generate(alias, challenge, strongBox = false)
            val chain = ks.getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()
            if (chain.size < 2) return State.Unavailable("the keystore gave no attestation certificates")
            State.Read(assess(chain, challenge, strongBox))
        } finally {
            runCatching { ks.deleteEntry(alias) }
        }
    }

    private fun generate(alias: String, challenge: ByteArray, strongBox: Boolean) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        gen.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(strongBox)
                .build()
        )
        gen.generateKeyPair()
    }

    private fun reason(t: Throwable): String = t.message?.takeIf { it.isNotBlank() }?.let { "${t.javaClass.simpleName}: $it" }
        ?: t.javaClass.simpleName
}
