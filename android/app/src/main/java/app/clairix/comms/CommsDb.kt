package app.clairix.comms

import android.content.Context
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.SecureRandom

/**
 * The key under which the comms database is encrypted, and the one-time
 * conversion of a database an earlier version kept in the clear.
 *
 * Message bodies were already sealed under [KeystoreBox]; everything around
 * them (who the contacts are and their keys, who was messaged when, the groups,
 * the receipts, the quarantine) was plain SQLite, so a copy of the app's files
 * was the owner's contact graph and history. The database is now SQLCipher,
 * which encrypts every page. Its key is 32 random bytes made here, wrapped by
 * [KeystoreBox] and kept in the comms identity preferences: the database is
 * ciphertext to anything that does not hold the Keystore key, which the wipe
 * and an unpair destroy.
 */
object CommsDb {

    const val NAME = "comms_e2ee.db"
    private const val PREFS = "comms_identity"
    private const val KEY_WRAPPED = "db_key_wrapped"
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Volatile private var loaded = false

    /** Loads SQLCipher's native library once; nothing in the library does it on its own. */
    fun loadLibrary() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }

    /**
     * The database's key as SQLCipher's raw-key spec (`x'…'`, so no key
     * derivation runs on every open), making a key the first time and
     * converting a plain database from an earlier version before it is opened.
     */
    @Synchronized
    fun password(context: Context, version: Int, name: String = NAME): String {
        loadLibrary()
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = app.getDatabasePath(name)
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        var key: ByteArray? = null
        if (wrapped != null) {
            // Only a key that is really gone (deleted by a wipe or an unpair), or
            // really another one, means the database is lost. A Keystore that
            // failed to answer this once is not that: the open fails, and the
            // next one tries again, rather than deleting every message.
            key = unwrapPatiently(wrapped, prefs)
        }
        if (key == null) {
            // No usable key. A sealed database left behind (its Keystore key
            // destroyed by a wipe or an unpair) is ciphertext nobody can open.
            if (file.exists() && !isPlain(file)) app.deleteDatabase(name)
            key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(KEY_WRAPPED, Base64.encodeToString(VaultBox.encrypt(key), Base64.NO_WRAP)).commit()
        }
        val spec = rawKey(key)
        if (file.exists() && isPlain(file)) {
            convert(app, file, spec, version)
            // Still plain: the conversion failed and said so. The history stays
            // usable, in the clear, and is converted on a later start; a key
            // against a plain file would open nothing at all.
            if (isPlain(file)) return ""
        }
        return spec
    }

    /**
     * The database key unwrapped, or null when its Keystore key is gone or is
     * another one. A Keystore failing to answer is asked again a few times over
     * a second before the open fails outright, never taken for a lost key.
     */
    private fun unwrapPatiently(wrapped: String, prefs: android.content.SharedPreferences): ByteArray? {
        // The phone is locked: the vault key refuses, which says nothing about
        // whether the database is lost. The open waits for the unlock.
        if (Vault.sealed.value) throw VaultSealedException()
        var failure: Exception? = null
        repeat(4) { attempt ->
            if (attempt > 0) Thread.sleep(250L * attempt)
            val existed = KeystoreBox.exists() ?: return@repeat
            try {
                // Under the vault key, or under KeystoreBox from before it, then rewrapped.
                val opened = VaultBox.open(Base64.decode(wrapped, Base64.NO_WRAP))
                if (opened.legacy) {
                    prefs.edit().putString(KEY_WRAPPED, Base64.encodeToString(VaultBox.encrypt(opened.plain), Base64.NO_WRAP)).commit()
                }
                return opened.plain
            } catch (e: javax.crypto.AEADBadTagException) {
                return null
            } catch (e: Exception) {
                if (!existed) return null
                failure = e
            }
        }
        throw IllegalStateException("The message database's key could not be read; it is kept, and tried again on the next start", failure)
    }

    /** Deletes the database and its key, for an unpair: the next open starts clean under a new key. */
    @Synchronized
    fun reset(context: Context) {
        val app = context.applicationContext
        app.deleteDatabase(NAME)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_WRAPPED).commit()
    }

    private fun rawKey(key: ByteArray): String = "x'" + key.joinToString("") { "%02x".format(it) } + "'"

    /** Whether [file] starts with SQLite's plain header; an encrypted database starts with its random salt. */
    private fun isPlain(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(PLAIN_HEADER.size)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            read == head.size && head.contentEquals(PLAIN_HEADER)
        }
    }.getOrDefault(false)

    /**
     * Copies a plain database into an encrypted one beside it and puts the
     * encrypted one in its place. The plain file is replaced only once the copy
     * is complete, so a failure leaves the history where it was, to be
     * converted on the next start rather than lost.
     */
    private fun convert(app: Context, file: File, spec: String, version: Int) {
        val sealed = File(file.parentFile, "${file.name}.sealed")
        sealed.delete()
        runCatching {
            // An empty key opens a plain database; SQLCipher's export copies
            // every table, index and row into the attached, keyed one.
            val plain = SQLiteDatabase.openDatabase(file.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null)
            try {
                val user = plain.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else version }
                plain.rawExecSQL("ATTACH DATABASE ? AS sealed KEY ?", sealed.path, spec)
                plain.rawExecSQL("SELECT sqlcipher_export('sealed')")
                plain.rawExecSQL("PRAGMA sealed.user_version = $user")
                plain.rawExecSQL("DETACH DATABASE sealed")
            } finally {
                plain.close()
            }
            // The journal and WAL files belong to the plain database and go with it.
            app.deleteDatabase(file.name)
            if (!sealed.renameTo(file)) throw IllegalStateException("could not move the sealed database into place")
            CommsLog.add("Message database sealed")
        }.onFailure {
            sealed.delete()
            CommsLog.add("The message database could not be sealed (${it.message}); it stays as it is until the next start")
        }
    }
}
