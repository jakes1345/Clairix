package app.clairix

/**
 * The Keystore does not exist on the test JVM, so a [Store] built with its default
 * cipher fails every write there (silently, as a failed write on a phone should) and
 * reopens empty. Tests that reopen a store use this instead: reversible, and visibly
 * not plaintext.
 */
object TestStoreCipher : StoreCipher {
    override fun seal(text: String): ByteArray = text.toByteArray(Charsets.UTF_8).map { (it.toInt() xor 0xFF).toByte() }.toByteArray()
    override fun open(blob: ByteArray): String = String(blob.map { (it.toInt() xor 0xFF).toByte() }.toByteArray(), Charsets.UTF_8)
}
