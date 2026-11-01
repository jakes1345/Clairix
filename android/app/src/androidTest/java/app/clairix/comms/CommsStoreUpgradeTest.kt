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

    @Test
    fun aVersion10DatabaseGainsPassesThatFollowAContactAndGoWithThem() {
        val old = CommsStore(context, name)
        try {
            old.upsertContact(ana)
            val db = old.writableDatabase
            db.execSQL("DROP TABLE passes")
            db.execSQL("DROP TABLE pass_notices")
            db.version = 10
        } finally {
            old.close()
        }

        val store = CommsStore(context, name)
        try {
            assertNull(store.pass(ana.number))
            // Every contact is owed the phone's pass once it is made.
            store.owePassToAll()
            assertEquals(listOf(ana.number), store.passNotices())

            val first = ByteArray(32) { 1 }
            val second = ByteArray(32) { 2 }
            assertTrue(store.setPass(ana.number, second, 2_000L))
            // An older key arriving late does not replace the newer one.
            assertTrue(!store.setPass(ana.number, first, 1_000L))
            assertTrue(store.pass(ana.number)!!.contentEquals(second))

            // A contact who moves keeps their pass and what they are owed.
            assertTrue(store.renumberContact(ana.number, "555000111", null, ana.ed25519, 3_000L))
            assertNull(store.pass(ana.number))
            assertTrue(store.pass("555000111")!!.contentEquals(second))
            assertEquals(listOf("555000111"), store.passNotices())

            store.clearPassNotice("555000111")
            assertTrue(store.passNotices().isEmpty())

            // Deleting the contact deletes their pass.
            store.deleteContact("555000111")
            assertNull(store.pass("555000111"))
        } finally {
            store.close()
        }
    }

    @Test
    fun aVersion11DatabaseGainsBlockingAndRequestsAndKeepsEveryContactAsItWas() {
        // What version 11 left on a phone: contacts without the blocked,
        // request and last_envelope columns, and no blocked list.
        val old = CommsStore(context, name)
        try {
            old.upsertContact(ana)
            val db = old.writableDatabase
            db.execSQL(
                "CREATE TABLE contacts_v11 AS SELECT number, name, ed25519, curve25519, sealing, signature, verified, key_changed, added_ts, relay, moved_ts FROM contacts"
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
                    added_ts INTEGER NOT NULL,
                    relay TEXT,
                    moved_ts INTEGER NOT NULL DEFAULT 0
                )"""
            )
            db.execSQL("INSERT INTO contacts SELECT * FROM contacts_v11")
            db.execSQL("DROP TABLE contacts_v11")
            db.execSQL("DROP TABLE blocked_keys")
            db.version = 11
            assertTrue("blocked" !in columns(old))
        } finally {
            old.close()
        }

        val sam = ana.copy(number = "111222333", name = "Sam", ed25519 = "ed-sam", curve25519 = "curve-sam", verified = false, request = true)
        val store = CommsStore(context, name)
        try {
            assertTrue(columns(store).containsAll(setOf("blocked", "request", "last_envelope")))
            assertEquals(ana, store.contact(ana.number))
            assertTrue(store.blockedKeys().isEmpty())

            // A stranger's first message: a request, kept apart from the contacts.
            store.upsertContact(sam)
            store.insertMessage(ChatMessage("m-sam", sam.number, Direction.IN, "hello", 2L, "received", read = false))
            assertNull(store.contact(sam.number))
            assertEquals(sam, store.contactEntry(sam.number))
            assertEquals(listOf(ana.number), store.contacts().map { it.number })
            assertEquals(listOf(sam.number), store.requestThreads().map { it.contact.number })
            assertEquals(1, store.requestThreads().single().unread)
            // Not in the badge, and owed no pass.
            assertEquals(0, store.unreadCount())
            store.owePassToAll()
            assertEquals(listOf(ana.number), store.passNotices())

            // Accepted: a contact, and a copy read before cannot undo it.
            store.setRequest(sam.number, false)
            store.upsertContact(sam.copy(name = "Sam R"))
            assertEquals(false, store.contact(sam.number)?.request)
            assertEquals("Sam R", store.contact(sam.number)?.name)
            assertEquals(1, store.unreadCount())

            // Blocked and kept: out of the contacts and the badge, its conversation kept,
            // nothing owed to it.
            store.owePass(sam.number)
            store.markBlocked(sam.number)
            assertNull(store.contact(sam.number))
            assertEquals(true, store.contactEntry(sam.number)?.blocked)
            assertEquals(listOf("m-sam"), store.messages(sam.number).map { it.id })
            assertEquals(listOf(ana.number), store.passNotices())
            assertEquals(0, store.unreadCount())
            assertEquals(listOf(sam.number), store.blockedContacts().map { it.number })

            // The blocked list, and a blocked member's group messages hidden until unblocked.
            val id = store.addBlocked(sam.ed25519, sam.curve25519, sam.number, null, "Sam", 3L)
            assertEquals(listOf(ContactGate.Blocked(id, sam.ed25519, sam.curve25519, sam.number, null, "Sam", 3L)), store.blockedKeys())
            store.insertGroup(Group("g1", "Family", ana.number, 1L, OrgRole.MEMBER))
            store.insertGroupMember(GroupMember("g1", sam.number, "Sam", OrgRole.MEMBER, sam.ed25519, ana.number, 1L))
            store.insertGroupMember(GroupMember("g1", ana.number, "Ana", OrgRole.ADMIN, ana.ed25519, ana.number, 1L))
            store.insertGroupMessage("g1", ChatMessage("g-sam", "g1", Direction.IN, "hi all", 4L, "received", read = false, sender = sam.number))
            store.insertGroupMessage("g1", ChatMessage("g-ana", "g1", Direction.IN, "hi", 5L, "received", read = false, sender = ana.number))
            assertEquals(listOf("g-ana"), store.groupMessages("g1").map { it.id })
            assertEquals(1, store.groupUnread("g1"))
            assertEquals(1, store.unreadCount())
            assertEquals(sam.number, store.removeBlocked(id)?.number)
            assertNull(store.removeBlocked(id))
            assertEquals(listOf("g-ana", "g-sam"), store.groupMessages("g1").map { it.id })

            // What a report names: the newest envelope they sent.
            assertNull(store.lastEnvelope(ana.number))
            store.noteEnvelope(ana.number, "ab".repeat(32))
            assertEquals("ab".repeat(32), store.lastEnvelope(ana.number))
        } finally {
            store.close()
        }

        // And it all survives the next start.
        val reopened = CommsStore(context, name)
        try {
            assertEquals(true, reopened.contactEntry(sam.number)?.blocked)
            assertEquals("ab".repeat(32), reopened.lastEnvelope(ana.number))
            assertEquals(ana, reopened.contact(ana.number))
        } finally {
            reopened.close()
        }
    }
}
