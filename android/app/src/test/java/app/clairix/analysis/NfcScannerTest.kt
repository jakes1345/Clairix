package app.clairix.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
