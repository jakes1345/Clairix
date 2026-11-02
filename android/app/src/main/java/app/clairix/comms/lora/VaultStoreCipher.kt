package app.clairix.comms.lora

import app.clairix.StoreCipher
import app.clairix.comms.VaultBox

/**
 * Seals a [app.clairix.Store] under the comms vault's data key ([VaultBox]), the
 * key the message database is under: it works only while the vault is open. For
 * what is said over the radio, which must be no easier to read off the phone than
 * the chats themselves.
 */
object VaultStoreCipher : StoreCipher {
    override fun seal(text: String): ByteArray = VaultBox.encrypt(text.toByteArray(Charsets.UTF_8))
    override fun open(blob: ByteArray): String = String(VaultBox.open(blob).plain, Charsets.UTF_8)
}
