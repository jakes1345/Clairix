package app.clairix.analysis

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import app.clairix.BuildConfig
import app.clairix.CardProfile
import app.clairix.NfcTag
import app.clairix.PendingVaultCapture

object NfcScanner {

    // Known EMV Application Identifier prefixes → network name
    private val PAYMENT_AIDS = listOf(
        "A0000000031010" to "Visa",
        "A0000000032010" to "Visa Electron",
        "A0000000033010" to "Visa V Pay",
        "A0000000034010" to "Visa",
        "A0000000041010" to "Mastercard",
        "A0000000043060" to "Mastercard Maestro",
        "A000000025010801" to "American Express",
        "A0000000250107" to "American Express",
        "A000000029DC01" to "Discover",
        "A0000001523010" to "Discover",
        "A0000002771010" to "Interac",
        "A0000000651010" to "JCB",
        "A0000003241010" to "UnionPay"
    )

    /**
     * MIFARE Classic is NXP's proprietary protocol; only phones with an NXP controller
     * can read it, and they say so with this system feature. Elsewhere Android never
     * offers the MifareClassic tech, for a genuine card or otherwise.
     */
    fun readsMifareClassic(context: Context): Boolean =
        runCatching { context.packageManager.hasSystemFeature(FEATURE_NXP_MIFARE) }.getOrDefault(false)

    /** A captured card: its identity, and the APDU pairs recorded for emulation. */
    data class VaultCapture(val tag: NfcTag, val apduPairs: List<Pair<String, String>>)

    /**
     * Reads [tag] to identify it. [mifareClassicSupported] is [readsMifareClassic]
     * for this phone; the emulator heuristic depends on it.
     *
     * This is the ordinary scan every tap takes. For an ISO-DEP card it sends only
     * SELECT PPSE and the AID SELECTs needed to name a payment network — never GET
     * PROCESSING OPTIONS, READ RECORD or the access-control AID probes, which read
     * or record card contents and run only in [capture]. The returned [NfcTag] holds
     * no APDU pairs.
     */
    fun parse(tag: Tag, mifareClassicSupported: Boolean): NfcTag =
        read(tag, mifareClassicSupported, captureForVault = false).tag

    /**
     * Reads [tag] for the explicit "Record for vault" re-tap: in addition to
     * identifying the card, it records the ISO 14443-4 conversation (AID SELECTs
     * and their EMV follow-ups for a non-payment card, or the access-control AID
     * probes when PPSE is refused) so the card can be emulated. Only ever called
     * under [BuildConfig.CARD_VAULT]; a payment card still records no card data.
     */
    fun capture(tag: Tag, mifareClassicSupported: Boolean): VaultCapture =
        read(tag, mifareClassicSupported, captureForVault = true)

    /** The profiles of a recognised payment card: never staged for the vault, saved or emulated. */
    private val PAYMENT_PROFILES = setOf(
        CardProfile.EMV_VISA, CardProfile.EMV_MASTERCARD, CardProfile.EMV_AMEX, CardProfile.EMV_OTHER
    )

    /** Whether [profile] is a recognised payment card's. */
    fun isPaymentProfile(profile: CardProfile): Boolean = profile in PAYMENT_PROFILES

    /**
     * What a "Record for vault" re-tap stages for the owner to name and save, or null
     * when there is nothing the vault may keep. The re-tap reads whatever card is held
     * to the phone next, not necessarily the one whose row armed it (many ISO-DEP cards
     * answer with a new random UID on every tap, so the UIDs cannot be matched), and a
     * payment card held there instead must not become savable: the vault never stores
     * or emulates one.
     */
    fun stagedCapture(capture: VaultCapture): PendingVaultCapture? =
        if (isPaymentProfile(capture.tag.profile)) null
        else PendingVaultCapture(capture.tag.uid, capture.tag.profile, capture.tag.type, capture.apduPairs)

    private fun read(tag: Tag, mifareClassicSupported: Boolean, captureForVault: Boolean): VaultCapture {
        val uid = tag.id.hex()
        val techs = tag.techList.map { it.substringAfterLast('.') }

        // ATQA + SAK (NFC-A only)
        var atqa: String? = null
        var sak: String? = null
        runCatching {
            NfcA.get(tag)?.let { a ->
                atqa = a.atqa.hex()
                sak  = "%02X".format(a.sak.toInt() and 0xFF)
            }
        }

        val profile = resolveProfile(techs, atqa, sak)

        // ISO-DEP: identify, and record APDU pairs only on a vault re-tap
        var isPayment = false
        var paymentNetwork: String? = null
        var apduPairs = emptyList<Pair<String, String>>()
        if (techs.contains("IsoDep")) {
            val iso = scanIsoDep(tag, captureForVault)
            isPayment      = iso.isPayment
            paymentNetwork = iso.network
            apduPairs      = iso.pairs
        }

        val payload = readNdef(tag)

        // Skimmer heuristics
        val flags = mutableListOf<String>()
        classicEmulatorFlag(techs, atqa, sak, mifareClassicSupported)?.let(flags::add)
        // Unidentified ISO-DEP with no NDEF, no payment signature, not a known access card type
        if (techs.contains("IsoDep") && !isPayment && payload == null &&
            profile == CardProfile.UNKNOWN) {
            flags += "Unidentified ISO 14443-4 smart card — verify origin"
        }
        // NFC-B card that isn't a passport or payment card (ISO 14443-B skimmers exist)
        if (techs.contains("NfcB") && !isPayment && !techs.contains("IsoDep")) {
            flags += "ISO 14443-B tag without standard app — uncommon; verify"
        }

        val suspicious = flags.isNotEmpty() && !isPayment

        val resolvedProfile = when {
            isPayment -> when (paymentNetwork) {
                "Visa", "Visa Electron", "Visa V Pay" -> CardProfile.EMV_VISA
                "Mastercard", "Mastercard Maestro"    -> CardProfile.EMV_MASTERCARD
                "American Express"                     -> CardProfile.EMV_AMEX
                else                                   -> CardProfile.EMV_OTHER
            }
            else -> profile
        }

        val type = if (resolvedProfile != CardProfile.UNKNOWN) resolvedProfile.label
                   else typeLabel(techs)

        val note = buildNote(resolvedProfile, isPayment, paymentNetwork, techs, payload)

        val tagInfo = NfcTag(
            uid           = uid,
            techs         = techs,
            type          = type,
            payload       = payload,
            suspicious    = suspicious,
            note          = note,
            ts            = System.currentTimeMillis(),
            atqa          = atqa,
            sak           = sak,
            profile       = resolvedProfile,
            paymentNetwork = paymentNetwork,
            skimmerFlags  = flags
        )
        // Pairs are returned to [capture] only; the tag kept in the UI carries none.
        return VaultCapture(tagInfo, if (captureForVault) apduPairs else emptyList())
    }

    /**
     * The emulator heuristic, on its own so it can be tested without a tag.
     *
     * A tag that answers anticollision with a MIFARE Classic ATQA *and* a Classic
     * SAK, on a phone whose controller reads Classic, is offered the MifareClassic
     * tech by Android. One that claims Classic in both and still is not was not
     * accepted as Classic by the controller: what clone and emulation hardware
     * answering with copied anticollision values looks like.
     *
     * The ISO-DEP bit alone says nothing. SAK 0x20 with ATQA 0x0004 is plain ISO
     * 14443-4 — JCOP badges, YubiKeys, phones in host card emulation — and used to be
     * flagged as a skimmer because 0x0004 is also Classic's ATQA. A Classic SAK is
     * now required; 0x20 is not one. Nor is anything flagged on a phone that cannot
     * read Classic at all, where a genuine Classic card lacks the tech too.
     */
    internal fun classicEmulatorFlag(
        techs: List<String>, atqa: String?, sak: String?, mifareClassicSupported: Boolean
    ): String? {
        if (!mifareClassicSupported) return null
        if (atqa == null || atqa.uppercase() !in CLASSIC_ATQAS) return null
        val sakByte = sak?.toIntOrNull(16) ?: return null
        if (sakByte !in CLASSIC_SAKS) return null
        if (techs.contains("MifareClassic")) return null
        return "ATQA/SAK claim MIFARE Classic but the phone's reader does not accept it as Classic — " +
            "possible card emulator (clone/skimmer hardware)"
    }

    /** System feature an NXP controller declares; see [readsMifareClassic]. */
    private const val FEATURE_NXP_MIFARE = "com.nxp.mifare"

    /**
     * ATQA as [NfcA.getAtqa] returns it (least significant byte first): Classic 1K
     * with a 4- or 7-byte UID, Classic 4K with a 4- or 7-byte UID, and 0x0062, which
     * was in the original list.
     */
    private val CLASSIC_ATQAS = setOf("0400", "4400", "0200", "4200", "6200")

    /**
     * SAKs of MIFARE Classic and of chips running Classic alongside ISO-DEP: Mini
     * 0x09, 1K 0x08, 4K 0x18, and the SmartMX/JCOP Classic-emulation values 0x28 and
     * 0x38. 0x20 — ISO-DEP alone — is deliberately absent.
     */
    private val CLASSIC_SAKS = setOf(0x08, 0x09, 0x18, 0x28, 0x38)

    // ── Profile resolution ──────────────────────────────────────────────────

    private fun resolveProfile(techs: List<String>, atqa: String?, sak: String?): CardProfile {
        if (techs.contains("NfcF")) return CardProfile.FELICA
        if (techs.contains("NfcV")) return CardProfile.ISO15693

        val sakByte = sak?.toIntOrNull(16) ?: return CardProfile.UNKNOWN

        return when {
            techs.contains("MifareClassic") -> when (sakByte) {
                0x09 -> CardProfile.MIFARE_CLASSIC_1K   // Mini
                0x08 -> CardProfile.MIFARE_CLASSIC_1K
                0x18 -> CardProfile.MIFARE_CLASSIC_4K
                0x10 -> CardProfile.MIFARE_PLUS         // Plus SL2 2K
                0x20 -> CardProfile.MIFARE_DESFIRE       // DESFire reported via MIFARE tech
                0x28 -> CardProfile.MIFARE_PLUS          // Plus SL3
                else -> CardProfile.MIFARE_CLASSIC_1K
            }
            techs.contains("MifareUltralight") -> CardProfile.MIFARE_ULTRALIGHT
            techs.contains("IsoDep") -> when {
                // DESFire has characteristic ATQA values
                atqa in setOf("0344", "4403", "0304", "0344") -> CardProfile.MIFARE_DESFIRE
                sakByte == 0x28 -> CardProfile.MIFARE_PLUS
                sakByte == 0x20 -> CardProfile.ISO14443_4
                else            -> CardProfile.ISO14443_4
            }
            else -> CardProfile.UNKNOWN
        }
    }

    // ── ISO-DEP / EMV scan ──────────────────────────────────────────────────

    internal data class IsoResult(
        val isPayment: Boolean,
        val network: String?,
        val pairs: List<Pair<String, String>>
    )

    /** SELECT PPSE — the first command any POS terminal sends. */
    private const val PPSE_CMD = "00A4040E" + "32504159" + "2E535953" + "2E444446" + "303100"

    /** SW1 SW2 == 90 00. */
    private fun isOk(sw: List<Byte>): Boolean =
        sw.size == 2 && sw[0] == 0x90.toByte() && sw[1] == 0x00.toByte()

    private fun scanIsoDep(tag: Tag, captureForVault: Boolean): IsoResult {
        val iso = IsoDep.get(tag) ?: return IsoResult(false, null, emptyList())
        return try {
            iso.connect()
            iso.timeout = 3000
            val result = runIsoDep({ cmd -> iso.transceive(cmd) }, captureForVault, BuildConfig.CARD_VAULT)
            iso.close()
            result
        } catch (_: Exception) {
            runCatching { iso.close() }
            IsoResult(false, null, emptyList())
        }
    }

    /**
     * The ISO-DEP exchange, over a [transceive] function so it can be driven without
     * a tag in a JVM test. It always identifies the card (SELECT PPSE, then the AID
     * SELECTs that name a payment network). It records the rest of the conversation —
     * the EMV follow-ups after a non-payment AID SELECT, or the access-control AID
     * probes when PPSE is refused — only when [fullCapture] holds, i.e. the build has
     * [BuildConfig.CARD_VAULT] on ([cardVaultBuild]) and this is an explicit
     * [captureForVault] re-tap. A recognised payment network is never followed up:
     * its records (PAN, expiry) are of no use here and reading them is the liability
     * this gate removes.
     */
    internal fun runIsoDep(
        transceive: (ByteArray) -> ByteArray,
        captureForVault: Boolean,
        cardVaultBuild: Boolean
    ): IsoResult {
        val fullCapture = cardVaultBuild && captureForVault
        val pairs = mutableListOf<Pair<String, String>>()

        val ppseResp = transceive(PPSE_CMD.hexToBytes())
        pairs += PPSE_CMD to ppseResp.hex()

        if (!isOk(ppseResp.takeLast(2))) {
            // PPSE refused. The card may still be an access-control card with a
            // well-known AID; probe for one only when recording for the vault.
            if (fullCapture) probeAccessAids(transceive, pairs)
            return IsoResult(false, null, pairs)
        }

        // Parse PPSE FCI for AIDs and identify a payment network.
        val aids = parsePpseAids(ppseResp)
        val network = aids.firstNotNullOfOrNull { aid ->
            PAYMENT_AIDS.firstOrNull { (prefix, _) ->
                aid.uppercase().startsWith(prefix.take(10))
            }?.second
        }

        // SELECT each discovered AID (cap at 3) to confirm the card answers. The EMV
        // follow-ups (GPO/READ RECORD) run only on a vault re-tap of a non-payment
        // card — stop after PPSE+SELECT when a payment network is recognised.
        for (aid in aids.take(3)) {
            val aidBytes = aid.hexToBytes()
            val selCmd = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00,
                aidBytes.size.toByte()) + aidBytes + byteArrayOf(0x00)
            runCatching {
                val selResp = transceive(selCmd)
                pairs += selCmd.hex() to selResp.hex()
                if (isOk(selResp.takeLast(2)) && fullCapture && network == null) {
                    captureEMVFollowUp(transceive, pairs)
                }
            }
        }

        // isPayment is only true when a recognised payment network was found. An
        // access-control card that answers PPSE but has no payment AID is not a
        // payment card — marking it one would block the user from saving it.
        return IsoResult(network != null, network, pairs)
    }

    // After a non-payment AID SELECT, record the standard follow-ups so the card can
    // later be emulated. Reached only under [fullCapture] in [runIsoDep].
    private fun captureEMVFollowUp(transceive: (ByteArray) -> ByteArray, pairs: MutableList<Pair<String, String>>) {
        // GET PROCESSING OPTIONS with empty PDOL
        val gpoCmd = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00, 0x00)
        runCatching {
            val resp = transceive(gpoCmd)
            pairs += gpoCmd.hex() to resp.hex()
        }
        // READ RECORD — SFI 1, record 1 (standard first EMV record location)
        val readCmd = byteArrayOf(0x00, 0xB2.toByte(), 0x01, 0x0C, 0x00)
        runCatching {
            val resp = transceive(readCmd)
            pairs += readCmd.hex() to resp.hex()
        }
    }

    // When PPSE is refused the card might still be an access-control card with a
    // well-known AID; selecting one gives the vault pairs it can replay. Reached only
    // under [fullCapture] in [runIsoDep].
    //
    // PIV — the U.S. federal / government-contractor badge (NIST SP 800-73, AIDs
    // A0000003080000100001 and A0000000620001) — was deliberately removed from this
    // list. No Clairix feature needs to emulate a federal badge; its PKI
    // challenge-response cannot be satisfied by static APDU replay in any case; and
    // probing government badge applications is a legal and policy liability with no
    // product benefit. See the "Card Vault and card emulation" section of
    // docs/audit/THREAT-MODEL.md. What remains are ordinary access and transit
    // application IDs a person may hold on a card of their own.
    private fun probeAccessAids(transceive: (ByteArray) -> ByteArray, pairs: MutableList<Pair<String, String>>) {
        val probes = listOf(
            "A00000000101",   // generic MIFARE / ISO 7816 card-management application
            "D2760000850100"  // CIPURSE (transit / access)
        )
        for (aidHex in probes) {
            runCatching {
                val aidBytes = aidHex.hexToBytes()
                val selCmd = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00,
                    aidBytes.size.toByte()) + aidBytes + byteArrayOf(0x00)
                val resp = transceive(selCmd)
                pairs += selCmd.hex() to resp.hex()
                if (isOk(resp.takeLast(2))) {
                    // Found a responding AID — record the follow-up and stop probing
                    captureEMVFollowUp(transceive, pairs)
                    return
                }
            }
        }
    }

    // TLV scan for tag 0x4F (AID) inside a PPSE FCI response
    private fun parsePpseAids(resp: ByteArray): List<String> {
        val aids = mutableListOf<String>()
        var i = 0
        val limit = resp.size - 2  // skip SW bytes
        while (i < limit - 1) {
            if ((resp[i].toInt() and 0xFF) == 0x4F) {
                val len = resp[i + 1].toInt() and 0xFF
                if (len in 5..16 && i + 2 + len <= limit) {
                    aids += resp.slice(i + 2 until i + 2 + len).toByteArray().hex()
                    i += 2 + len
                    continue
                }
            }
            i++
        }
        return aids.distinct()
    }

    // ── NDEF ────────────────────────────────────────────────────────────────

    private fun readNdef(tag: Tag): String? = try {
        val ndef = Ndef.get(tag) ?: return null
        ndef.connect()
        val msg: NdefMessage = ndef.cachedNdefMessage ?: run { ndef.close(); return null }
        ndef.close()
        msg.records.mapNotNull { decodeRecord(it) }.joinToString(" | ").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun decodeRecord(r: NdefRecord): String? {
        val p = r.payload
        if (p.isEmpty()) return null
        return when (r.tnf) {
            NdefRecord.TNF_WELL_KNOWN -> when {
                r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                    val langLen = p[0].toInt() and 0x3F
                    String(p, 1 + langLen, p.size - 1 - langLen, Charsets.UTF_8)
                }
                r.type.contentEquals(NdefRecord.RTD_URI) ->
                    uriPrefix(p[0].toInt() and 0xFF) + String(p, 1, p.size - 1, Charsets.UTF_8)
                else -> p.hex()
            }
            NdefRecord.TNF_MIME_MEDIA  -> "MIME:${String(r.type)} ${p.hex().take(32)}"
            NdefRecord.TNF_ABSOLUTE_URI -> String(p, Charsets.UTF_8)
            else -> p.hex().take(32)
        }
    }

    private fun uriPrefix(code: Int) = when (code) {
        0x01 -> "http://www."; 0x02 -> "https://www."
        0x03 -> "http://";     0x04 -> "https://"
        0x05 -> "tel:";        0x06 -> "mailto:"
        else -> ""
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun typeLabel(techs: List<String>) = when {
        techs.contains("IsoDep")         -> "ISO 14443-4"
        techs.contains("MifareClassic")  -> "MIFARE Classic"
        techs.contains("MifareUltralight") -> "MIFARE Ultralight"
        techs.contains("Ndef")           -> "NDEF"
        techs.contains("NfcV")           -> "ISO 15693"
        techs.contains("NfcF")           -> "FeliCa"
        else                             -> "Unknown"
    }

    private fun buildNote(
        profile: CardProfile, isPayment: Boolean, network: String?,
        techs: List<String>, payload: String?
    ) = when {
        isPayment && network != null -> "$network contactless payment card"
        isPayment                    -> "Payment / transit card"
        profile == CardProfile.MIFARE_CLASSIC_1K || profile == CardProfile.MIFARE_CLASSIC_4K ->
            "MIFARE Classic — office badge / parking / building access credential"
        profile == CardProfile.MIFARE_DESFIRE ->
            "MIFARE DESFire — high-security access card (challenge-response, Flipper-proof)"
        profile == CardProfile.MIFARE_PLUS ->
            "MIFARE Plus SL3 — secure access card (AES-128 encrypted, clone-resistant)"
        profile == CardProfile.MIFARE_ULTRALIGHT ->
            "MIFARE Ultralight — disposable NFC sticker or event ticket"
        profile == CardProfile.FELICA ->
            "FeliCa — Japanese transit or payment card"
        profile == CardProfile.ISO15693 ->
            "ISO 15693 HF RFID tag — typically inventory or asset label"
        techs.contains("IsoDep") && payload == null ->
            "ISO 14443-4 smart card — no readable payload (access credential)"
        payload?.startsWith("https://") == true -> "NFC URL tag"
        else -> "Generic NFC tag"
    }

    fun ByteArray.hex()   = joinToString("") { "%02X".format(it) }
    private fun String.hexToBytes(): ByteArray {
        val s = replace(" ", "")
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
