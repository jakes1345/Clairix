package app.clairix.comms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone updating from a version whose contacts had no relay column (database
 * version 6) keeps every contact, reads each as on its own relay, and can then
 * store contacts on other relays. Runs on the device against the real
 * SQLCipher database and Keystore key, in a database of its own so the app's
 * is never touched.
 */
@RunWith(AndroidJUnit4::class)
class CommsStoreUpgradeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "comms_upgrade_test.db"

    private val ana = Contact(
        number = "482913605", name = "Ana", ed25519 = "ed-ana", curve25519 = "curve-ana",
        sealing = "seal-ana", signature = "sig-ana", verified = true, addedTs = 1_700_000_000_000L
    )

    @Before
    fun clean() { context.deleteDatabase(name) }

    @After
    fun tidy() { context.deleteDatabase(name) }

    private fun columns(store: CommsStore): Set<String> =
        store.readableDatabase.rawQuery("PRAGMA table_info(contacts)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toSet()
        }

    @Test
    fun aVersion6DatabaseKeepsItsContactsAndGainsTheRelayColumn() {
        // Build what version 6 left on a phone: the contacts table without the
        // relay column, and the database marked as version 6.
        val old = CommsStore(context, name)
        try {
            old.upsertContact(ana)
            val db = old.writableDatabase
            db.execSQL(
                "CREATE TABLE contacts_v6 AS SELECT number, name, ed25519, curve25519, sealing, signature, verified, key_changed, added_ts FROM contacts"
            )
            db.execSQL("DROP TABLE contacts")
            db.execSQL(
                """CREATE TABLE contacts (
                    number TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    ed25519 TEXT NOT NULL,
                    curve25519 TEXT NOT NULL UNIQUE,
                    sealing TEXT NOT NULL,
                    signature TEXT NOT NULL,
                    verified INTEGER NOT NULL DEFAULT 0,
                    key_changed INTEGER NOT NULL DEFAULT 0,
                    added_ts INTEGER NOT NULL
                )"""
            )
            db.execSQL("INSERT INTO contacts SELECT * FROM contacts_v6")
            db.execSQL("DROP TABLE contacts_v6")
            db.version = 6
            assertTrue("relay" !in columns(old))
        } finally {
            old.close()
        }

        // The update opens it.
        val upgraded = CommsStore(context, name)
        try {
            assertTrue("relay" in columns(upgraded))
            val read = upgraded.contact(ana.number)
            assertNotNull("the contact from before the update is gone", read)
            assertEquals(ana, read)
            assertNull(read!!.relay)

            val bo = ana.copy(number = "111222333", name = "Bo", curve25519 = "curve-bo", relay = "https://relay.example.org")
            upgraded.upsertContact(bo)
            assertEquals("https://relay.example.org", upgraded.contact(bo.number)?.relay)
            // Moving a contact back to this relay clears the column.
            upgraded.upsertContact(bo.copy(relay = null))
            assertNull(upgraded.contact(bo.number)?.relay)
            upgraded.upsertContact(bo)
        } finally {
            upgraded.close()
        }

        // And it survives the next start.
        val reopened = CommsStore(context, name)
        try {
            assertEquals("https://relay.example.org", reopened.contact("111222333")?.relay)
            assertEquals(ana, reopened.contact(ana.number))
        } finally {
            reopened.close()
        }
    }
}
