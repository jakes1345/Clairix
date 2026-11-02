package app.clairix.nav

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The asset key list (relays/keys.json; tools/relays/check_keys.py holds the
 * same rules): a list is taken only on the built-in key's signature, an older
 * one is never taken back, and a published file then checks under a listed key
 * that is not revoked, the next key included, and under no other. Real Ed25519
 * signatures, made and checked by the JDK as the app's crypto crate does.
 */
class AssetKeysTest {

    private val now = 1_800_000_000L

    /** An Ed25519 key pair, its public half as android/asset-signing.pub writes one. */
    private class Ed {
        private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val key: String = Base64.getEncoder().withoutPadding().encodeToString(pair.public.encoded.copyOfRange(12, 44))
        fun sign(message: ByteArray): String = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(message)
            Base64.getEncoder().withoutPadding().encodeToString(sign())
        }
    }

    /** verify(key, message, signature) as the crypto crate's: the raw key under its X.509 header. */
    private val jdkVerify: (String, ByteArray, String) -> Boolean = { key, message, signature ->
        val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + Base64.getDecoder().decode(key.trimEnd('='))
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(message)
            verify(Base64.getDecoder().decode(signature))
        }
    }

    private val root = Ed()
    private val current = Ed()
    private val next = Ed()
    private val leaked = Ed()

    private fun entry(key: String, role: String, revoked: Boolean = false) = JSONObject().put("key", key).put("role", role).put("revoked", revoked)

    private fun list(issued: Long = 1_700_000_000L, vararg keys: JSONObject = arrayOf(entry(current.key, "current"), entry(next.key, "next"))) = JSONObject()
        .put("type", "clairix-asset-keys").put("version", 1).put("issued", issued).put("keys", JSONArray(keys.toList()))

    private fun refused(doc: JSONObject) {
        try {
            AssetKeyList.parse(doc.toString(), now)
            fail("accepted: $doc")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private val verifyRoot: (ByteArray, String) -> Boolean = { message, signature -> jdkVerify(root.key, message, signature) }

    @Test
    fun aGoodListParsesAndAcceptsItsCurrentAndNextKeys() {
        val parsed = AssetKeyList.parse(list().toString(), now)
        assertEquals(1_700_000_000L, parsed.issued)
        assertEquals(listOf(current.key, next.key), parsed.accepted)
    }

    @Test
    fun theListInTheRepositoryParsesAndAcceptsTheBuiltInKey() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "relays/keys.json").isFile }
        val parsed = AssetKeyList.parse(File(repo, "relays/keys.json").readText(), System.currentTimeMillis() / 1000)
        val builtIn = File(repo, "android/asset-signing.pub").readText().trim()
        assertTrue(builtIn.trimEnd('=') in parsed.accepted.map { it.trimEnd('=') })
    }

    @Test
    fun aBadListIsRefused() {
        refused(list().put("type", "clairix-relay-list"))
        refused(list().put("version", 2))
        refused(list(issued = now + 7200))
        refused(list().put("keys", JSONArray()))
        refused(list(1_700_000_000L, *Array(9) { entry(Ed().key, "next", revoked = true) }))
        refused(list(1_700_000_000L, entry("not base64!", "current")))
        refused(list(1_700_000_000L, entry(Base64.getEncoder().encodeToString(ByteArray(31)), "current")))
        // The spare bits of the last character set: the same key under another spelling.
        refused(list(1_700_000_000L, entry(current.key.dropLast(1) + spareBitsSet(current.key.last()), "current")))
        refused(list(1_700_000_000L, entry(current.key, "old")))
        refused(list(1_700_000_000L, entry(current.key, "current").put("revoked", "no")))
        refused(list(1_700_000_000L, entry(current.key, "current").apply { remove("revoked") }))
        refused(list(1_700_000_000L, entry(current.key, "current").put("extra", 1)))
        refused(list().put("extra", "field"))
        // Listed twice, padded once and not the other time.
        refused(list(1_700_000_000L, entry(current.key, "current"), entry("${current.key}=", "next", revoked = true)))
        // Two live keys in one role; and a list in which every key is revoked.
        refused(list(1_700_000_000L, entry(current.key, "current"), entry(next.key, "current")))
        refused(list(1_700_000_000L, entry(current.key, "current", revoked = true)))
    }

    /** The base64 character with the same top two bits as [c] and some of its four spare bits set. */
    private fun spareBitsSet(c: Char): Char {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return alphabet[(alphabet.indexOf(c) and 0b110000) or 0b0101]
    }

    @Test
    fun revokedKeysPileUpButAreNotAccepted() {
        val parsed = AssetKeyList.parse(
            list(1_700_000_000L, entry(leaked.key, "current", revoked = true), entry(current.key, "current"), entry(next.key, "next")).toString(), now
        )
        assertEquals(listOf(current.key, next.key), parsed.accepted)
    }

    @Test
    fun aListIsTakenOnlyOnTheBuiltInKeysSignatureAndOnlyWhenIssuedLater() {
        val held = AssetKeyList.parse(list(issued = 1_700_000_000L).toString(), now)
        val newer = list(issued = 1_700_000_100L).toString().toByteArray()
        assertEquals(1_700_000_100L, AssetKeyList.accept(held, newer, root.sign(newer), now, verifyRoot)?.issued)
        // Signed by a key the held list accepts, even the next one, it is still refused: a list cannot vouch for itself.
        for (signer in listOf(current, next, leaked)) {
            try {
                AssetKeyList.accept(held, newer, signer.sign(newer), now, verifyRoot)
                fail("a list not signed by the built-in key was accepted")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        // An old list handed back, in which a key since revoked still counted, is not taken.
        val older = list(issued = 1_699_999_000L, entry(leaked.key, "current")).toString().toByteArray()
        assertNull(AssetKeyList.accept(held, older, root.sign(older), now, verifyRoot))
        val same = list(issued = 1_700_000_000L).toString().toByteArray()
        assertNull(AssetKeyList.accept(held, same, root.sign(same), now, verifyRoot))
    }

    @Test
    fun aPublishedFileChecksUnderAListedKeyThatIsNotRevokedAndNoOther() {
        val file = """{"type": "clairix-relay-list"}""".toByteArray()
        val held = AssetKeyList.parse(
            list(1_700_000_000L, entry(leaked.key, "current", revoked = true), entry(current.key, "current"), entry(next.key, "next")).toString(), now
        )
        val keys = AssetKeyList.acceptedKeys(root.key, held)
        assertTrue(AssetKeyList.verifiedByAny(keys, file, current.sign(file), jdkVerify))
        assertTrue("the next key is accepted ahead of the switch", AssetKeyList.verifiedByAny(keys, file, next.sign(file), jdkVerify))
        assertFalse("a revoked key is refused", AssetKeyList.verifiedByAny(keys, file, leaked.sign(file), jdkVerify))
        assertFalse("a key the list does not name is refused", AssetKeyList.verifiedByAny(keys, file, Ed().sign(file), jdkVerify))
        assertFalse("the built-in key signs lists, and files only while listed", AssetKeyList.verifiedByAny(keys, file, root.sign(file), jdkVerify))
        assertFalse("a file changed after signing is refused", AssetKeyList.verifiedByAny(keys, file + byteArrayOf(1), current.sign(file), jdkVerify))
        assertFalse("garbage for a signature is refused", AssetKeyList.verifiedByAny(keys, file, "not a signature", jdkVerify))
        // With no list held, the built-in key alone.
        val bare = AssetKeyList.acceptedKeys(root.key, null)
        assertEquals(listOf(root.key), bare)
        assertTrue(AssetKeyList.verifiedByAny(bare, file, root.sign(file), jdkVerify))
        assertFalse(AssetKeyList.verifiedByAny(bare, file, current.sign(file), jdkVerify))
    }

    @Test
    fun aPaddedKeyChecksLikeItsBareSpelling() {
        val file = "x".toByteArray()
        val held = AssetKeyList.parse(list(1_700_000_000L, entry("${current.key}=", "current")).toString(), now)
        assertTrue(AssetKeyList.verifiedByAny(held.accepted, file, current.sign(file), jdkVerify))
    }
}
