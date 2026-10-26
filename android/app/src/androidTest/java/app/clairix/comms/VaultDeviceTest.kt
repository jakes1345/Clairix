package app.clairix.comms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * The vault key and the locked inbox on a real Keystore. (The emulator has no
 * secure lock screen, so the vault key works throughout; that it refuses on a
 * locked phone is Android's to enforce.)
 */
@RunWith(AndroidJUnit4::class)
class VaultDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var inbox: LockedInbox

    @Before
    fun setUp() {
        inbox = LockedInbox(context)
        inbox.clear()
    }

    @After
    fun tearDown() {
        inbox.clear()
    }

    @Test
    fun theVaultKeyOpensWhatItSealedAndTellsAnOldBlobApart() {
        val secret = Random.nextBytes(32)
        val sealed = VaultBox.encrypt(secret)
        val opened = VaultBox.open(sealed)
        assertArrayEquals(secret, opened.plain)
        assertFalse(opened.legacy)
        // Written under the key that works while locked, before the vault existed:
        // opened, and reported so the caller writes it again under the vault key.
        val old = VaultBox.open(KeystoreBox.encrypt(secret))
        assertArrayEquals(secret, old.plain)
        assertTrue(old.legacy)
        // Neither key opens what neither sealed.
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { VaultBox.open(tampered) }.isFailure)
    }

    @Test
    fun theLockedInboxKeepsEnvelopesInOrderAndUnreadableOnDisk() {
        val first = RelayClient.Envelope("a", 1_000L, Random.nextBytes(300), RelayClient.ALERT_CALL)
        val second = RelayClient.Envelope("b", 2_000L, Random.nextBytes(300))
        inbox.put(second)
        inbox.put(first)
        inbox.put(first) // a second copy (the socket and a fetch both handed it over)
        assertEquals(2, inbox.count())
        val all = inbox.all()
        assertEquals(listOf("a", "b"), all.map { it.envelope.id })
        assertArrayEquals(first.data, all[0].envelope.data)
        assertEquals(RelayClient.ALERT_CALL, all[0].envelope.alert)
        assertEquals(null, all[1].envelope.alert)
        // On disk the relay's bytes do not appear: an envelope copied off the relay
        // cannot be matched to a file here.
        val files = File(context.filesDir, "locked_inbox").listFiles()!!.filter { it.name.endsWith(".bin") }
        for (f in files) {
            val bytes = f.readBytes()
            assertFalse("envelope bytes on disk", bytes.toList().windowed(32).any { w -> w == first.data.take(32) || w == second.data.take(32) })
        }
        inbox.remove(all[0])
        assertEquals(listOf("b"), inbox.all().map { it.envelope.id })
    }
}
