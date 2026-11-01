package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emulator heuristic: Classic claims that the reader rejects, never plain ISO-DEP. */
class NfcScannerTest {

    private val isoDep = listOf("NfcA", "IsoDep")

    @Test
    fun plainIsoDepWithAtqa0004IsNeverAnEmulator() {
        // JCOP badges, YubiKeys and phones in HCE mode all answer SAK 0x20, ATQA 0x0004.
        assertNull(NfcScanner.classicEmulatorFlag(isoDep, "0400", "20", mifareClassicSupported = true))
        assertNull(NfcScanner.classicEmulatorFlag(isoDep, "0400", "20", mifareClassicSupported = false))
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA", "IsoDep", "Ndef"), "4400", "20", mifareClassicSupported = true))
    }

    @Test
    fun aGenuineClassicCardIsClear() {
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA", "MifareClassic", "NdefFormatable"), "0400", "08", true))
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA", "MifareClassic"), "0200", "18", true))
        // JCOP with Classic emulation exposes both techs.
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA", "MifareClassic", "IsoDep"), "0400", "28", true))
    }

    @Test
    fun aClassicClaimTheReaderDoesNotAcceptIsFlagged() {
        assertNotNull(NfcScanner.classicEmulatorFlag(listOf("NfcA"), "0400", "08", true))
        assertNotNull(NfcScanner.classicEmulatorFlag(listOf("NfcA"), "4400", "18", true))
        assertNotNull(NfcScanner.classicEmulatorFlag(isoDep, "0400", "28", true))
    }

    @Test
    fun nothingIsFlaggedOnAPhoneThatCannotReadClassic() {
        // Without an NXP controller Android never offers MifareClassic, even for a real card.
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA"), "0400", "08", mifareClassicSupported = false))
    }

    @Test
    fun missingOrMalformedValuesAreNotFlagged() {
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA"), null, "08", true))
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA"), "0400", null, true))
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA"), "0400", "zz", true))
        // An Ultralight / NTAG ATQA with a non-Classic SAK.
        assertNull(NfcScanner.classicEmulatorFlag(listOf("NfcA", "MifareUltralight"), "4400", "00", true))
    }

    // ── The ISO-DEP exchange and the capture gate ────────────────────────────
    //
    // runIsoDep is driven over a transceive function so it can be tested without a
    // tag. GPO (80 A8 …) and READ RECORD (00 B2 …) are the EMV follow-ups that read
    // card records; the access-AID SELECT (00 A4 04 00 …) is the probe.

    private fun bytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    private fun List<Pair<String, String>>.hasCmd(prefix: String) = any { it.first.startsWith(prefix, ignoreCase = true) }

    /** A Visa payment card: answers PPSE and the AID SELECT, and would answer a follow-up. */
    private fun emvVisa(cmd: ByteArray): ByteArray = when {
        hex(cmd).startsWith("00A4040E") -> bytes("4F07A00000000310109000") // PPSE FCI carrying the Visa AID
        hex(cmd).startsWith("00A40400") -> bytes("9000")                   // AID SELECT accepted
        hex(cmd).startsWith("80A8")     -> bytes("77089000")               // GPO — must never be asked of a payment card
        hex(cmd).startsWith("00B2")     -> bytes("70059F13029000")         // READ RECORD — likewise
        else -> bytes("6A82")
    }

    /** An access card that refuses PPSE but answers an access-AID probe and its follow-up. */
    private fun accessCard(cmd: ByteArray): ByteArray = when {
        hex(cmd).startsWith("00A4040E") -> bytes("6A82")   // PPSE refused
        hex(cmd).startsWith("00A40400") -> bytes("9000")   // a probe AID answers
        hex(cmd).startsWith("80A8")     -> bytes("77089000")
        hex(cmd).startsWith("00B2")     -> bytes("70059000")
        else -> bytes("6A82")
    }

    /** An access card whose non-payment AID is listed in PPSE. */
    private fun accessViaPpse(cmd: ByteArray): ByteArray = when {
        hex(cmd).startsWith("00A4040E") -> bytes("4F06A000000001019000") // PPSE FCI, non-payment AID
        hex(cmd).startsWith("00A40400") -> bytes("9000")
        hex(cmd).startsWith("80A8")     -> bytes("77089000")
        hex(cmd).startsWith("00B2")     -> bytes("70059000")
        else -> bytes("6A82")
    }

    @Test
    fun anEmvProfileYieldsNoGpoOrReadRecordPairs() {
        // A recognised payment card is identified by PPSE + AID SELECT and never
        // followed up with GET PROCESSING OPTIONS or READ RECORD — whether or not the
        // tap is a vault re-tap on a vault build.
        for (capture in listOf(false, true)) {
            val r = NfcScanner.runIsoDep(::emvVisa, captureForVault = capture, cardVaultBuild = true)
            assertTrue("a Visa card should be recognised (capture=$capture)", r.isPayment)
            assertEquals("Visa", r.network)
            assertFalse("GPO was sent to a payment card (capture=$capture)", r.pairs.hasCmd("80A8"))
            assertFalse("READ RECORD was sent to a payment card (capture=$capture)", r.pairs.hasCmd("00B2"))
        }
    }

    @Test
    fun anOrdinaryScanNeverFollowsUpANonPaymentCard() {
        // PPSE answers with a non-payment AID. An ordinary scan selects it to identify
        // it, but records no GPO/READ RECORD; a vault re-tap on a vault build does.
        val ordinary = NfcScanner.runIsoDep(::accessViaPpse, captureForVault = false, cardVaultBuild = true)
        assertFalse(ordinary.isPayment)
        assertTrue("the AID should still be selected to identify the card", ordinary.pairs.hasCmd("00A40400"))
        assertFalse("ordinary scan must not send GPO", ordinary.pairs.hasCmd("80A8"))
        assertFalse("ordinary scan must not send READ RECORD", ordinary.pairs.hasCmd("00B2"))

        val recording = NfcScanner.runIsoDep(::accessViaPpse, captureForVault = true, cardVaultBuild = true)
        assertTrue("a vault re-tap records the follow-up", recording.pairs.hasCmd("80A8") || recording.pairs.hasCmd("00B2"))
    }

    @Test
    fun accessAidProbesRunOnlyWhenRecordingForTheVaultOnAVaultBuild() {
        // PPSE refused: the only further commands would be the access-AID probes.
        // An ordinary scan sends none — just the one PPSE command.
        val ordinary = NfcScanner.runIsoDep(::accessCard, captureForVault = false, cardVaultBuild = true)
        assertEquals("ordinary scan sends PPSE only", 1, ordinary.pairs.size)
        assertTrue(ordinary.pairs.single().first.startsWith("00A4040E", ignoreCase = true))

        // A build without the vault never probes, even when asked to capture.
        val noVaultBuild = NfcScanner.runIsoDep(::accessCard, captureForVault = true, cardVaultBuild = false)
        assertEquals("a non-vault build never probes", 1, noVaultBuild.pairs.size)

        // A vault re-tap on a vault build: the probe SELECT and its follow-up are recorded.
        val recording = NfcScanner.runIsoDep(::accessCard, captureForVault = true, cardVaultBuild = true)
        assertTrue("the probe SELECT is recorded", recording.pairs.hasCmd("00A40400"))
        assertTrue("the follow-up is recorded", recording.pairs.hasCmd("80A8") || recording.pairs.hasCmd("00B2"))
        assertFalse("an access card is not a payment card", recording.isPayment)
    }
}
