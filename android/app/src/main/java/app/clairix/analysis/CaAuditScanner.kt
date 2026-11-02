package app.clairix.analysis

import android.content.Context
import android.provider.Settings
import app.clairix.HealthFact
import app.clairix.Severity
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * A certificate authority this phone trusts that it should not, or that nobody
 * can vouch for. [severity] is HIGH for a CA someone installed by hand (the
 * standard way to intercept TLS on a phone one has physical access to) and
 * MEDIUM for a root in the system store that is not part of the AOSP bundle,
 * which a carrier or vendor image can add.
 */
data class SuspiciousCa(
    val issuerDn: String,
    val subjectDn: String,
    /** SHA-256 of the DER certificate, colon-separated hex. */
    val fingerprint: String,
    val validFrom: Long,
    val validTo: Long,
    val severity: Severity = Severity.HIGH,
    /** "user" for a hand-installed CA, "system" for one in the OS store. */
    val source: String = "user",
    val reason: String = ""
) {
    /** The CN of the subject, or the whole DN when it has none. */
    val subjectCn: String get() = CaAuditScanner.cn(subjectDn) ?: subjectDn
}

/**
 * One trust-store audit: the roots worth a look ([roots]) and which of the two sources could be
 * read. [userStoreRead] is the one that matters most: the AndroidCAStore is the only place a root
 * installed by hand shows up (this app's network security config trusts system roots only, so the
 * default trust manager never lists one), and an audit that could not read it knows nothing about
 * them, which is not the same as "none". [error] says why a source could not be read, in a few
 * words, for the page; null when both were. [unreadableUser] counts certificates under a "user:"
 * alias that would not open: installed by hand, though nothing more can be said of them.
 */
data class CaAudit(
    val roots: List<SuspiciousCa>,
    val userStoreRead: Boolean,
    val trustManagerRead: Boolean,
    val error: String? = null,
    val unreadableUser: Int = 0
) {
    /** Both sources were read. */
    val complete: Boolean get() = userStoreRead && trustManagerRead
}

/** What the Certificates page shows for an audit; see [CaAuditScanner.page]. */
sealed interface CaPage {
    /** The audit is still running. */
    data object Reading : CaPage

    /** Both sources were read and nothing needs a look: the only state that may say the store is clean. */
    data object Clean : CaPage

    /**
     * [roots] to list. When a source could not be read, [failure] ("Could not read the trust store
     * (…)") and [failureDetail] go above them in caution; [unreadableUser] certificates installed by
     * hand would not open.
     */
    data class Findings(
        val roots: List<SuspiciousCa>,
        val failure: String? = null,
        val failureDetail: String? = null,
        val unreadableUser: Int = 0
    ) : CaPage
}

/**
 * Audits the trust store: which root certificates this phone will accept a TLS
 * server certificate from.
 *
 * Two sources are read. The AndroidCAStore keystore lists the phone's roots by
 * alias, and its aliases say where each one came from: "system:<hash>" or
 * "user:<hash>". A root under a "user:" alias was installed through Settings →
 * Security → Install a certificate, which is exactly what a stalker with the PIN
 * or an MDM would do to read the phone's HTTPS traffic through a proxy. It is
 * the only source that can show one: the default X.509 trust manager, the
 * second source, answers with this app's network security config, which trusts
 * the system's roots and no others. What that trust manager accepts is the
 * operative list of system roots; one it accepts that the keystore does not list
 * as a system root came from somewhere else.
 *
 * Anything not under a user alias is compared against the AOSP bundle below.
 * The bundle changes a little with every Android release, so a system root not
 * in the list is reported as MEDIUM, worth a look, not an alarm.
 *
 * Either read can fail. The audit says which did ([CaAudit]), so a failure is
 * shown as one and never as a clean store.
 */
object CaAuditScanner {

    /**
     * Subject CNs of roots shipped in AOSP's system CA bundle (system/ca-certificates).
     * Representative rather than exhaustive; a root whose CN is not here is also
     * accepted when its organisation is in [KNOWN_SYSTEM_ORGS].
     */
    val KNOWN_SYSTEM_CA_CN: Set<String> = setOf(
        "GTS Root R1", "GTS Root R2", "GTS Root R3", "GTS Root R4",
        "GlobalSign Root CA", "GlobalSign", "GlobalSign Root R46", "GlobalSign Root E46",
        "DigiCert Global Root CA", "DigiCert Global Root G2", "DigiCert Global Root G3",
        "DigiCert High Assurance EV Root CA", "DigiCert Assured ID Root CA", "DigiCert Trusted Root G4",
        "DigiCert TLS RSA4096 Root G5", "DigiCert TLS ECC P384 Root G5",
        "Baltimore CyberTrust Root",
        "ISRG Root X1", "ISRG Root X2",
        "COMODO RSA Certification Authority", "COMODO ECC Certification Authority", "COMODO Certification Authority",
        "USERTrust RSA Certification Authority", "USERTrust ECC Certification Authority",
        "AAA Certificate Services",
        "Sectigo Public Server Authentication Root R46", "Sectigo Public Server Authentication Root E46",
        "Entrust Root Certification Authority", "Entrust Root Certification Authority - G2",
        "Entrust Root Certification Authority - EC1", "Entrust Root Certification Authority - G4",
        "Go Daddy Root Certificate Authority - G2", "Go Daddy Class 2 Certification Authority",
        "Starfield Root Certificate Authority - G2", "Starfield Class 2 Certification Authority",
        "Starfield Services Root Certificate Authority - G2",
        "Amazon Root CA 1", "Amazon Root CA 2", "Amazon Root CA 3", "Amazon Root CA 4",
        "Microsoft RSA Root Certificate Authority 2017", "Microsoft ECC Root Certificate Authority 2017",
        "QuoVadis Root CA 1 G3", "QuoVadis Root CA 2", "QuoVadis Root CA 2 G3", "QuoVadis Root CA 3", "QuoVadis Root CA 3 G3",
        "SwissSign Gold CA - G2", "SwissSign Silver CA - G2",
        "T-TeleSec GlobalRoot Class 2", "T-TeleSec GlobalRoot Class 3",
        "SecureTrust CA", "Secure Global CA",
        "Certum Trusted Network CA", "Certum Trusted Network CA 2", "Certum EC-384 CA", "Certum Trusted Root CA",
        "Buypass Class 2 Root CA", "Buypass Class 3 Root CA",
        "Actalis Authentication Root CA",
        "D-TRUST Root Class 3 CA 2 2009", "D-TRUST BR Root CA 1 2020", "D-TRUST EV Root CA 1 2020",
        "Hongkong Post Root CA 1", "Hongkong Post Root CA 3",
        "SSL.com Root Certification Authority RSA", "SSL.com Root Certification Authority ECC",
        "SSL.com EV Root Certification Authority RSA R2", "SSL.com EV Root Certification Authority ECC",
        "IdenTrust Commercial Root CA 1", "IdenTrust Public Sector Root CA 1",
        "Security Communication RootCA2", "Security Communication RootCA3", "Security Communication ECC RootCA1",
        "SecureSign RootCA11",
        "XRamp Global Certification Authority",
        "TWCA Root Certification Authority", "TWCA Global Root CA",
        "Certigna", "Certigna Root CA",
        "Atos TrustedRoot 2011", "Atos TrustedRoot Root CA RSA TLS 2021", "Atos TrustedRoot Root CA ECC TLS 2021",
        "AffirmTrust Commercial", "AffirmTrust Networking", "AffirmTrust Premium", "AffirmTrust Premium ECC",
        "emSign Root CA - G1", "emSign ECC Root CA - G3", "emSign Root CA - C1", "emSign ECC Root CA - C3",
        "Hellenic Academic and Research Institutions RootCA 2015",
        "Hellenic Academic and Research Institutions ECC RootCA 2015",
        "HARICA TLS RSA Root CA 2021", "HARICA TLS ECC Root CA 2021",
        "OISTE WISeKey Global Root GB CA", "OISTE WISeKey Global Root GC CA",
        "NetLock Arany (Class Gold) Főtanúsítvány",
        "Chambers of Commerce Root - 2008", "Global Chambersign Root - 2008",
        "Izenpe.com",
        "Staat der Nederlanden EV Root CA",
        "e-Szigno Root CA 2017", "Microsec e-Szigno Root CA 2009",
        "GDCA TrustAUTH R5 ROOT",
        "UCA Global G2 Root", "UCA Extended Validation Root",
        "vTrus Root CA", "vTrus ECC Root CA",
        "Telia Root CA v2", "TeliaSonera Root CA v1",
        "ANF Secure Server Root CA",
        "Certainly Root R1", "Certainly Root E1",
        "TunTrust Root CA",
        "Autoridad de Certificacion Firmaprofesional CIF A62634068",
        "Trustwave Global Certification Authority",
        "Trustwave Global ECC P256 Certification Authority", "Trustwave Global ECC P384 Certification Authority",
        "E-Tugra Global Root CA RSA v3", "E-Tugra Global Root CA ECC v3",
        "Cybertrust Global Root",
        "Network Solutions Certificate Authority",
        "ePKI Root Certification Authority",
        "SZAFIR ROOT CA2",
        "BJCA Global Root CA1", "BJCA Global Root CA2",
        "CommScope Public Trust RSA Root-01", "CommScope Public Trust RSA Root-02",
        "CommScope Public Trust ECC Root-01", "CommScope Public Trust ECC Root-02",
        "TrustAsia Global Root CA G3", "TrustAsia Global Root CA G4",
        "Sectigo Public Server Authentication Root R46",
        "AC RAIZ FNMT-RCM", "AC RAIZ FNMT-RCM SERVIDORES SEGUROS",
        "CA Disig Root R2",
        "Certum Trusted Network CA",
        "Hongkong Post Root CA 3",
        "NAVER Global Root Certification Authority",
        "Security Communication RootCA2",
        "Telekom Security TLS ECC Root 2020", "Telekom Security TLS RSA Root 2023",
        "D-Trust SBR Root CA 1 2022", "D-Trust SBR Root CA 2 2022",
        "FIRMAPROFESIONAL CA ROOT-A WEB",
        "SwissSign RSA TLS Root CA 2022 - 1",
        "TWCA CYBER Root CA",
        "TWCA Global Root CA"
    )

    /** Organisations (the O= attribute) of the AOSP bundle, matched as substrings. */
    val KNOWN_SYSTEM_ORGS: Set<String> = setOf(
        "Google Trust Services", "GlobalSign", "DigiCert", "Comodo", "COMODO", "Sectigo", "The USERTRUST Network",
        "Internet Security Research Group", "Entrust", "GoDaddy.com", "Starfield Technologies", "Amazon",
        "Microsoft Corporation", "QuoVadis", "SwissSign", "T-Systems", "Deutsche Telekom", "SecureTrust",
        "Unizeto", "Asseco Data Systems", "Buypass", "Actalis", "D-Trust", "Hongkong Post", "SSL Corporation",
        "IdenTrust", "SECOM Trust", "Japan Certification Services", "XRamp", "TAIWAN-CA", "Dhimyotis", "Atos",
        "AffirmTrust", "eMudhra", "Hellenic Academic", "WISeKey", "NetLock", "AC Camerfirma", "IZENPE",
        "Staat der Nederlanden", "Microsec", "GUANG DONG CERTIFICATE AUTHORITY", "UniTrust", "iTrusChina",
        "Telia", "TeliaSonera", "ANF Autoridad de Certificacion", "Certainly", "Agence Nationale de Certification Electronique",
        "Firmaprofesional", "Trustwave", "E-Tuğra", "E-Tugra", "Cybertrust", "Baltimore", "Network Solutions",
        "Chunghwa Telecom", "Krajowa Izba Rozliczeniowa", "BEIJING CERTIFICATE AUTHORITY", "CommScope",
        "TrustAsia", "FNMT-RCM", "Disig", "NAVER", "Telekom Security", "Let's Encrypt"
    )

    private val hex = "0123456789ABCDEF".toCharArray()

    /** Where a root installed by hand is listed, and taken out. */
    private const val USER_CREDENTIALS = "Settings → Security → Encryption & credentials → User credentials"

    private const val USER_REASON =
        "Installed by hand on this phone. A user-added root lets whoever added it decrypt " +
            "HTTPS traffic of browsers and apps that trust user CAs, by routing the phone through a proxy. " +
            "Remove it under $USER_CREDENTIALS unless you put it there yourself."
    private const val SYSTEM_REASON =
        "A root in the system store that is not part of the standard Android bundle. Carrier and " +
            "manufacturer images sometimes add roots; a modified system image can too. Confirm it is expected."
    private const val UNKNOWN_REASON =
        "Trusted by this phone but not listed in the system or user store. Only a modified trust " +
            "manager or OS component can do that; treat the phone's TLS as compromised until explained."

    /** One certificate as the audit needs it, apart from the X.509 object, so [audit] runs without one. */
    internal data class Root(
        val fingerprint: String,
        val subjectDn: String,
        val issuerDn: String,
        val validFrom: Long,
        val validTo: Long
    )

    /**
     * What the AndroidCAStore listed: the roots under "user:" aliases, the fingerprints of those
     * under "system:" ones, and how many "user:" aliases would not open.
     */
    internal data class StoreListing(val user: List<Root>, val system: Set<String>, val unreadableUser: Int = 0)

    /**
     * The audit. Reads the keystore and the trust manager and parses every root; a few
     * milliseconds on a modern phone but file I/O, so call it off the main thread. Never throws:
     * a source that cannot be read is said to be unread in the result.
     */
    fun scan(context: Context): CaAudit = runCatching {
        audit(runCatching { readStore() }, runCatching { readAccepted() })
    }.getOrElse { CaAudit(emptyList(), userStoreRead = false, trustManagerRead = false, error = "the audit: ${reason(it)}") }

    /** Source 1: the AndroidCAStore, which tells roots installed by hand from system roots. */
    private fun readStore(): StoreListing {
        val ks = KeyStore.getInstance("AndroidCAStore").also { it.load(null, null) }
        val user = ArrayList<Root>()
        val system = HashSet<String>()
        var unreadable = 0
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val root = runCatching { (ks.getCertificate(alias) as? X509Certificate)?.let(::root) }.getOrNull()
            when {
                alias.startsWith("user:") -> if (root == null) unreadable++ else user += root
                alias.startsWith("system:") -> root?.let { system += it.fingerprint }
            }
        }
        return StoreListing(user, system, unreadable)
    }

    /** Source 2: what the default trust manager accepts, under this app's network security config. */
    private fun readAccepted(): List<Root> {
        val tmf = TrustManagerFactory.getInstance("X509").apply { init(null as KeyStore?) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
            .mapNotNull { runCatching { root(it) }.getOrNull() }
    }

    /**
     * The audit from what the two sources gave. A source that failed, or that listed nothing at
     * all (a working phone always has its system roots, in both), counts as unread, and the
     * result says why. With the store unread, a root the trust manager accepts is taken as a
     * system root, which is all the trust manager takes: nothing can show it came from outside
     * the store, and a read that failed is no reason to call one "trusted from outside".
     */
    internal fun audit(store: Result<StoreListing>, accepted: Result<List<Root>>): CaAudit {
        val listing = store.getOrNull()?.takeIf { it.user.isNotEmpty() || it.system.isNotEmpty() || it.unreadableUser > 0 }
        val storeError = store.exceptionOrNull()?.let { "the phone's certificate store: ${reason(it)}" }
            ?: if (listing == null) "the phone's certificate store listed nothing" else null
        val roots = accepted.getOrNull()?.takeIf { it.isNotEmpty() }
        val acceptedError = accepted.exceptionOrNull()?.let { "the roots this phone accepts: ${reason(it)}" }
            ?: if (roots == null) "the phone listed no roots it accepts" else null

        val out = LinkedHashMap<String, SuspiciousCa>()
        listing?.user?.forEach { out[it.fingerprint] = describe(it, Severity.HIGH, "user", USER_REASON) }
        for (r in roots.orEmpty()) {
            if (r.fingerprint in out) continue
            if (isKnownSystemRoot(r.subjectDn)) continue
            val system = listing == null || r.fingerprint in listing.system
            out[r.fingerprint] = if (system) describe(r, Severity.MEDIUM, "system", SYSTEM_REASON)
            else describe(r, Severity.HIGH, "unknown", UNKNOWN_REASON)
        }
        return CaAudit(
            roots = out.values.sortedWith(compareByDescending<SuspiciousCa> { it.severity.ordinal }.thenBy { it.subjectCn }),
            userStoreRead = listing != null,
            trustManagerRead = roots != null,
            error = listOfNotNull(storeError, acceptedError).joinToString("; ").ifEmpty { null },
            unreadableUser = listing?.unreadableUser ?: 0
        )
    }

    /** An exception in a few words: its class, and its message when it has a short one. */
    private fun reason(e: Throwable): String =
        e.javaClass.simpleName + (e.message?.trim()?.takeIf { it.isNotEmpty() }?.let { ": " + it.take(80) } ?: "")

    /**
     * What the Certificates page shows for [audit] (null while it runs). The clean card only when
     * both sources were read and nothing turned up; a source that could not be read is said so in
     * caution, above whatever the other source found, never as a clean store.
     */
    fun page(audit: CaAudit?): CaPage {
        if (audit == null) return CaPage.Reading
        if (audit.complete && audit.roots.isEmpty() && audit.unreadableUser == 0) return CaPage.Clean
        if (audit.complete) return CaPage.Findings(audit.roots, unreadableUser = audit.unreadableUser)
        val detail = if (!audit.userStoreRead) {
            "Android did not hand over the certificates installed on this phone, and a certificate installed by hand " +
                "shows up nowhere else, so this page cannot say there is none. Look yourself: $USER_CREDENTIALS lists " +
                "every certificate someone installed."
        } else {
            "The roots this phone accepts could not be read, so a system root that is not in the standard bundle, or " +
                "one trusted from outside both stores, would not show here. The certificates installed by hand were read" +
                (if (audit.roots.none { it.source == "user" } && audit.unreadableUser == 0) ": there are none." else ", and are listed below.")
        }
        return CaPage.Findings(
            roots = audit.roots,
            failure = "Could not read the trust store (${audit.error ?: "no reason given"})",
            failureDetail = detail,
            unreadableUser = audit.unreadableUser
        )
    }

    /**
     * Phone check's certificates row, from [scan]'s result: the roots someone installed by hand,
     * and any trusted from outside both stores, are HIGH, since either lets whoever holds the key
     * read the phone's encrypted traffic. A system root missing from the stock bundle stays on the
     * Certificates page only: the bundle list here is representative, not complete, and a maker's
     * or carrier's own root is common. A store that could not be read is not "none added by hand":
     * the row says it could not be read, at MEDIUM, and where to look instead.
     */
    fun fact(audit: CaAudit): HealthFact {
        val user = audit.roots.filter { it.source == "user" }
        val unknown = audit.roots.filter { it.source == "unknown" }
        val byHand = user.size + audit.unreadableUser
        val acceptedNote = if (audit.trustManagerRead) "" else
            "The roots this phone accepts could not be read (${audit.error ?: "no reason given"}), so one trusted from " +
                "outside both stores would not show here."
        if (byHand == 0 && unknown.isEmpty()) {
            if (!audit.userStoreRead) return HealthFact(
                "user_ca", "Network", "Installed certificates", "Could not read the trust store", Severity.MEDIUM,
                "Android did not hand over the certificates installed on this phone (${audit.error ?: "no reason given"}), " +
                    "and a certificate installed by hand shows up nowhere else, so this does not mean there is none. Look " +
                    "yourself: anything under $USER_CREDENTIALS was installed by hand.",
                fixAction = Settings.ACTION_SECURITY_SETTINGS
            )
            return HealthFact(
                "user_ca", "Network", "Installed certificates", "None added by hand",
                detail = acceptedNote.ifEmpty { null }
            )
        }
        val value = listOfNotNull(
            byHand.takeIf { it > 0 }?.let { "$it added by hand" },
            unknown.size.takeIf { it > 0 }?.let { "$it from outside the stores" }
        ).joinToString(", ")
        val listed = user + unknown
        val detail = buildString {
            if (listed.isNotEmpty()) {
                val names = listed.take(3).joinToString(", ") { it.subjectCn } + if (listed.size > 3) ", …" else ""
                append("Trusted roots: $names. ")
            }
            if (audit.unreadableUser > 0) {
                append(
                    "${audit.unreadableUser} installed by hand ${if (audit.unreadableUser == 1) "is" else "are"} on the phone " +
                        "but would not open, so nothing more can be said of ${if (audit.unreadableUser == 1) "it" else "them"}. "
                )
            }
            if (byHand > 0) {
                append(
                    "A root installed by hand lets whoever holds its key read this phone's encrypted traffic through a proxy, " +
                        "which is how someone who has had the phone and its PIN, or a management profile, reads it. Unless you " +
                        "installed it yourself, remove it under $USER_CREDENTIALS. "
                )
            }
            if (unknown.isNotEmpty()) {
                append(
                    "A root trusted from outside both the system and the user store takes a modified trust manager or OS " +
                        "component; treat the phone's encrypted connections as readable until that is explained. "
                )
            }
            if (acceptedNote.isNotEmpty()) append(acceptedNote).append(' ')
            append("Phone check → Certificates shows each one.")
        }
        return HealthFact(
            "user_ca", "Network", "Installed certificates", value, Severity.HIGH, detail,
            fixAction = Settings.ACTION_SECURITY_SETTINGS
        )
    }

    /** True when the subject names a root in the AOSP bundle by CN or by organisation. */
    fun isKnownSystemRoot(subjectDn: String): Boolean {
        val cn = cn(subjectDn)
        if (cn != null && cn in KNOWN_SYSTEM_CA_CN) return true
        val org = attribute(subjectDn, "O") ?: return false
        return KNOWN_SYSTEM_ORGS.any { org.contains(it, ignoreCase = true) }
    }

    private fun root(cert: X509Certificate) = Root(
        fingerprint = fingerprint(cert),
        subjectDn = cert.subjectX500Principal.getName(X500Principal.RFC2253),
        issuerDn = cert.issuerX500Principal.getName(X500Principal.RFC2253),
        validFrom = cert.notBefore.time,
        validTo = cert.notAfter.time
    )

    private fun describe(r: Root, severity: Severity, source: String, reason: String) = SuspiciousCa(
        issuerDn = r.issuerDn,
        subjectDn = r.subjectDn,
        fingerprint = r.fingerprint,
        validFrom = r.validFrom,
        validTo = r.validTo,
        severity = severity,
        source = source,
        reason = reason
    )

    /** SHA-256 of the DER encoding, "AB:CD:…". */
    fun fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val sb = StringBuilder(digest.size * 3)
        for ((i, b) in digest.withIndex()) {
            if (i > 0) sb.append(':')
            sb.append(hex[(b.toInt() shr 4) and 0xF]).append(hex[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    /** The CN attribute of an RFC 2253 DN, or null. */
    fun cn(dn: String): String? = attribute(dn, "CN")

    /**
     * One attribute of an RFC 2253 DN. Values with escaped commas ("\,") are kept
     * whole; a quoted value has its quotes removed.
     */
    fun attribute(dn: String, type: String): String? {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var escaped = false
        var quoted = false
        for (ch in dn) {
            when {
                escaped -> { sb.append(ch); escaped = false }
                ch == '\\' -> { sb.append(ch); escaped = true }
                ch == '"' -> { quoted = !quoted; sb.append(ch) }
                (ch == ',' || ch == '+') && !quoted -> { parts += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) parts += sb.toString()
        val prefix = "$type="
        val raw = parts.map { it.trim() }.firstOrNull { it.startsWith(prefix, ignoreCase = true) } ?: return null
        return raw.substring(prefix.length).trim().removeSurrounding("\"").replace("\\,", ",").replace("\\+", "+")
            .takeIf { it.isNotEmpty() }
    }
}
