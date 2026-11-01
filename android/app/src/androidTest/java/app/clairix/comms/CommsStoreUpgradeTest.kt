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

    private fun columns(store: CommsStore, table: String = "contacts"): Set<String> =
        store.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toSet()
        }

    private fun tables(store: CommsStore): Set<String> =
        store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
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

    @Test
    fun aVersion12DatabaseGainsTheGroupOutboxAndRosterNumbersAndKeepsEveryRow() {
        // What version 12 left on a phone: groups and members without roster
        // numbers, and no group outbox; with a contact, a group, a group
        // message, a one-to-one message still queued and a blocked identity.
        val bo = ana.copy(number = "111222333", name = "Bo", ed25519 = "ed-bo", curve25519 = "curve-bo", verified = false)
        val old = CommsStore(context, name)
        try {
            old.upsertContact(ana)
            old.upsertContact(bo)
            old.insertGroup(Group("g1", "Family", ana.number, 1L, OrgRole.ADMIN))
            old.insertGroupMember(GroupMember("g1", ana.number, "Ana", OrgRole.ADMIN, ana.ed25519, ana.number, 1L))
            old.insertGroupMember(GroupMember("g1", bo.number, "Bo", OrgRole.MEMBER, bo.ed25519, ana.number, 2L))
            old.insertGroupMessage("g1", ChatMessage("g-out", "g1", Direction.OUT, "on my way", 3L, "sent", read = true, sender = ana.number))
            old.insertMessage(ChatMessage("m-queued", bo.number, Direction.OUT, "still going", 4L, "queued", read = true))
            old.addBlocked("ed-x", "curve-x", "999888777", null, "X", 5L)
            val db = old.writableDatabase
            db.execSQL("CREATE TABLE groups_v12 AS SELECT id, name, created_by, created_ts, my_role FROM groups")
            db.execSQL("DROP TABLE groups")
            db.execSQL(
                """CREATE TABLE groups (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    created_ts INTEGER NOT NULL,
                    my_role TEXT NOT NULL DEFAULT 'MEMBER'
                )"""
            )
            db.execSQL("INSERT INTO groups SELECT * FROM groups_v12")
            db.execSQL("DROP TABLE groups_v12")
            db.execSQL("CREATE TABLE group_members_v12 AS SELECT gid, number, name, role, ed25519, added_by, added_ts FROM group_members")
            db.execSQL("DROP TABLE group_members")
            db.execSQL(
                """CREATE TABLE group_members (
                    gid TEXT NOT NULL,
                    number TEXT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    role TEXT NOT NULL DEFAULT 'MEMBER',
                    ed25519 TEXT NOT NULL DEFAULT '',
                    added_by TEXT NOT NULL DEFAULT '',
                    added_ts INTEGER NOT NULL,
                    PRIMARY KEY (gid, number)
                )"""
            )
            db.execSQL("INSERT INTO group_members SELECT * FROM group_members_v12")
            db.execSQL("DROP TABLE group_members_v12")
            db.execSQL("DROP TABLE group_outbox")
            db.version = 12
            assertTrue("roster_version" !in columns(old, "groups"))
            assertTrue("group_outbox" !in tables(old))
        } finally {
            old.close()
        }

        val store = CommsStore(context, name)
        try {
            assertTrue(columns(store, "groups").containsAll(setOf("roster_version", "roster_seen")))
            assertTrue("rv" in columns(store, "group_members"))
            assertTrue("group_outbox" in tables(store))
            // Every row from before is there as it was, the group unnumbered.
            assertEquals(ana, store.contact(ana.number))
            assertEquals(bo, store.contact(bo.number))
            assertEquals(Group("g1", "Family", ana.number, 1L, OrgRole.ADMIN), store.group("g1"))
            assertEquals(listOf(ana.number, bo.number), store.groupMembers("g1").map { it.number })
            assertTrue(store.groupMembers("g1").all { it.rv == 0L })
            assertEquals(listOf("g-out"), store.groupMessages("g1").map { it.id })
            assertEquals(listOf("m-queued"), store.queued().map { it.id })
            assertEquals(listOf("999888777"), store.blockedKeys().map { it.number })
            assertTrue(store.outbox().isEmpty())

            // Roster numbers: set by setRoster, raised and never lowered by what members say.
            store.setRoster("g1", 3L, 3L)
            store.noteRosterSeen("g1", 5L)
            store.noteRosterSeen("g1", 4L)
            assertEquals(3L to 5L, store.group("g1")!!.let { it.rosterVersion to it.rosterSeen })
            store.noteMemberVersion("g1", bo.number, 5L)
            store.noteMemberVersion("g1", bo.number, 2L)
            assertEquals(5L, store.groupMember("g1", bo.number)!!.rv)
            // Writing the group again (its name, this phone's role) keeps its numbers.
            store.insertGroup(Group("g1", "Family and friends", ana.number, 1L, OrgRole.ADMIN))
            assertEquals("Family and friends", store.group("g1")!!.name)
            assertEquals(3L to 5L, store.group("g1")!!.let { it.rosterVersion to it.rosterSeen })
            // A member written again keeps the highest number while their key stays the same.
            store.insertGroupMember(GroupMember("g1", bo.number, "Bo", OrgRole.MEMBER, bo.ed25519, ana.number, 2L, rv = 1L))
            assertEquals(5L, store.groupMember("g1", bo.number)!!.rv)
            store.insertGroupMember(GroupMember("g1", bo.number, "Bo", OrgRole.MEMBER, "ed-new", ana.number, 2L, rv = 1L))
            assertEquals("under another key it is someone else", 1L, store.groupMember("g1", bo.number)!!.rv)

            // The group outbox: a row per member and payload, its payload sealed and read back whole.
            val sos = GroupOutbox.Row(
                "p1", bo.number, "g1", "batch-1", GroupOutbox.Kind.SOS, bo.ed25519, """{"v":1,"t":"panic","gid":"g1"}""",
                10L, 10L + GroupOutbox.SOS_LIFETIME_MS
            )
            val roster = sos.copy(id = "r1", batch = "batch-2", kind = GroupOutbox.Kind.ROSTER, payload = """{"v":1,"t":"gctl","op":"roster"}""")
            store.insertOutbox(listOf(sos, roster))
            store.insertOutbox(listOf(sos.copy(status = GroupOutbox.Status.SENT)))
            val rows = store.outbox()
            assertEquals("a second copy of a row is not added", listOf("p1", "r1"), rows.map { it.id })
            assertEquals(sos.copy(seq = rows[0].seq), rows[0])
            assertEquals(listOf("r1"), store.outbox(listOf(GroupOutbox.Kind.ROSTER)).map { it.id })
            store.updateOutbox(rows[0].copy(attempts = 2, triedAt = 20L, error = "busy", mesh = true))
            val tried = store.outboxBatch("batch-1").single()
            assertEquals(2, tried.attempts)
            assertEquals(20L, tried.triedAt)
            assertEquals("busy", tried.error)
            assertTrue(tried.mesh)
            // A newer SOS takes over from the rows of the one before; the group going takes the rest.
            store.deleteOutboxKindExcept(GroupOutbox.Kind.SOS, "batch-3")
            assertEquals(listOf("r1"), store.outbox().map { it.id })
            store.insertOutbox(listOf(sos.copy(id = "p2", batch = "batch-3")))
            store.deleteGroup("g1")
            assertTrue(store.outbox().isEmpty())
        } finally {
            store.close()
        }

        // And it survives the next start.
        val reopened = CommsStore(context, name)
        try {
            assertEquals(ana, reopened.contact(ana.number))
            assertEquals(listOf("m-queued"), reopened.queued().map { it.id })
            assertTrue("group_outbox" in tables(reopened))
            assertTrue(reopened.outbox().isEmpty())
        } finally {
            reopened.close()
        }
    }
}
