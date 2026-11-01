package app.clairix.comms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who reaches this phone: the blocked list, message requests and what a report says. */
class ContactGateTest {

    private val abuser = ContactGate.Blocked(
        id = 1L, ed25519 = "ED-ABUSER", curve25519 = "CURVE-ABUSER", number = "482913605",
        relay = null, name = "Sam", ts = 1_700_000_000_000L
    )
    private val elsewhere = abuser.copy(id = 2L, ed25519 = "ED-OTHER", curve25519 = "CURVE-OTHER", number = "111222333", relay = "https://relay.example.org")

    // ── The blocked list ─────────────────────────────────────────────────

    @Test
    fun eitherKeyOfABlockedIdentityIsEnoughWhateverNumberItClaims() {
        val list = listOf(abuser)
        assertTrue(ContactGate.isBlocked(list, "ED-ABUSER", "CURVE-NEW", "999888777", null))
        assertTrue(ContactGate.isBlocked(list, "ED-NEW", "CURVE-ABUSER", "999888777", "https://moved.example"))
        // Keys alone, before any number has been read from the payload.
        assertTrue(ContactGate.isBlocked(list, "ED-ABUSER", null, null, null))
        assertTrue(ContactGate.isBlocked(list, null, "CURVE-ABUSER", null, null))
    }

    @Test
    fun aBlockedNumberIsBlockedOnItsOwnRelayOnly() {
        val list = listOf(abuser, elsewhere)
        // The same number with new keys on this phone's relay: still blocked.
        assertTrue(ContactGate.isBlocked(list, "ED-REINSTALLED", "CURVE-REINSTALLED", "482913605", null))
        // The same digits on another relay are someone else.
        assertFalse(ContactGate.isBlocked(list, "ED-X", "CURVE-X", "482913605", "https://relay.example.org"))
        assertTrue(ContactGate.isBlocked(list, "ED-X", "CURVE-X", "111222333", "https://relay.example.org"))
        assertFalse(ContactGate.isBlocked(list, "ED-X", "CURVE-X", "111222333", null))
    }

    @Test
    fun nothingKnownBlocksNothingAndAnEmptyListBlocksNoOne() {
        assertFalse(ContactGate.isBlocked(listOf(abuser), null, null, null, null))
        assertFalse(ContactGate.isBlocked(listOf(abuser), "", "", null, null))
        assertFalse(ContactGate.isBlocked(emptyList(), "ED-ABUSER", "CURVE-ABUSER", "482913605", null))
        // A row missing a key (it never should) does not match an empty one.
        val keyless = abuser.copy(ed25519 = "", curve25519 = "")
        assertFalse(ContactGate.matches(keyless, "", "", "123456789", null))
    }

    // ── Message requests ─────────────────────────────────────────────────

    private val oneToOne: List<JSONObject> = listOf(
        CommsWire.message("m1", 1L, "hi"),
        CommsWire.callOffer("c1", 1L, "v=0"),
        CommsWire.payment("p1", "tx", 1L, 5L, null),
        CommsWire.voicemail("v1", 1L, "AAAA", 1000L),
        CommsWire.mediaHeader("x1", 1L, "image/jpeg", 1, 1, 0, 10, 1, null, null),
        CommsWire.timer("t1", 1L, 300),
        CommsWire.moved(1L),
        // An invitation brings a group this phone is not in yet: a stranger's is a request.
        CommsWire.groupCtrl("g1", CommsWire.OP_ADD),
    )

    private val groupTraffic: List<JSONObject> = listOf(
        CommsWire.groupMsg("g1", "hi all", "gm1", 1L),
        CommsWire.groupCtrl("g1", CommsWire.OP_LEAVE),
        CommsWire.threat("g1", "t1", 1L, "CATCHER", "HIGH", 1.0, 2.0, "t", "d", 2L),
        CommsWire.panic("g1", "s1", 1L, null, null, null),
        CommsWire.timer("t2", 1L, 300, gid = "g1"),
    )

    @Test
    fun aStrangersFirstMessageCallPaymentOrInvitationIsARequest() {
        for (p in oneToOne) {
            assertEquals(CommsWire.type(p), ContactGate.Admission.REQUEST, ContactGate.admit(p, "ED-NEW", rosterEd25519 = null, ownRelay = true))
            // Even from someone a group lists: only what they say to the group lets them in.
            assertEquals(CommsWire.type(p), ContactGate.Admission.REQUEST, ContactGate.admit(p, "ED-NEW", rosterEd25519 = "ED-NEW", ownRelay = true))
        }
    }

    @Test
    fun aGroupMemberWritingToTheGroupComesInAsAContact() {
        for (p in groupTraffic) {
            val type = CommsWire.type(p)
            assertEquals(type, ContactGate.Admission.CONTACT, ContactGate.admit(p, "ED-MEMBER", rosterEd25519 = "ED-MEMBER", ownRelay = false))
            // The roster knows other keys for that number: someone else holds it.
            assertEquals(type, ContactGate.Admission.REQUEST, ContactGate.admit(p, "ED-IMPOSTOR", rosterEd25519 = "ED-MEMBER", ownRelay = true))
            // Not on the roster, or the group is not on this phone.
            assertEquals(type, ContactGate.Admission.REQUEST, ContactGate.admit(p, "ED-MEMBER", rosterEd25519 = null, ownRelay = true))
            // A roster with no key vouches only for a number this phone's relay stands behind.
            assertEquals(type, ContactGate.Admission.CONTACT, ContactGate.admit(p, "ED-MEMBER", rosterEd25519 = "", ownRelay = true))
            assertEquals(type, ContactGate.Admission.REQUEST, ContactGate.admit(p, "ED-MEMBER", rosterEd25519 = "", ownRelay = false))
        }
    }

    @Test
    fun aRequestTheRosterVouchesForStillGetsTheGroupsMessagesAndSos() {
        // Their first word was private, so they are a request; the roster records exactly their key.
        assertTrue(ContactGate.rosterVouches(request = true, blocked = false, ed25519 = "ed-bob", rosterEd25519 = "ed-bob"))
        // Another key on the roster, or none recorded, vouches for nobody.
        assertFalse(ContactGate.rosterVouches(request = true, blocked = false, ed25519 = "ed-bob", rosterEd25519 = "ed-other"))
        assertFalse(ContactGate.rosterVouches(request = true, blocked = false, ed25519 = "ed-bob", rosterEd25519 = ""))
        assertFalse(ContactGate.rosterVouches(request = true, blocked = false, ed25519 = "ed-bob", rosterEd25519 = null))
        // Someone blocked gets nothing, roster or not.
        assertFalse(ContactGate.rosterVouches(request = true, blocked = true, ed25519 = "ed-bob", rosterEd25519 = "ed-bob"))
    }

    @Test
    fun onlyRemovingOrBlockingAnOrdinaryContactReplacesThePassKey() {
        assertTrue(ContactGate.mayHoldPass(request = false, blocked = false))
        // A request was never given the pass; a blocked record lost it when blocked.
        assertFalse(ContactGate.mayHoldPass(request = true, blocked = false))
        assertFalse(ContactGate.mayHoldPass(request = false, blocked = true))
        assertFalse(ContactGate.mayHoldPass(request = true, blocked = true))
    }

    @Test
    fun someoneWhoJoinedWithThisPhonesInviteComesInAsAContact() {
        val joined = CommsWire.joined("A".repeat(22))
        assertEquals(ContactGate.Admission.CONTACT, ContactGate.admit(joined, "ED-NEW", rosterEd25519 = null, ownRelay = true, invited = true))
        assertEquals(ContactGate.Admission.CONTACT, ContactGate.admit(joined, "ED-NEW", rosterEd25519 = null, ownRelay = false, invited = true))
        // Naming an invite this phone does not know (or no longer does) is a request like any other.
        assertEquals(ContactGate.Admission.REQUEST, ContactGate.admit(joined, "ED-NEW", rosterEd25519 = null, ownRelay = true, invited = false))
        // Word of an invite after a first message accepts the request it made.
        assertEquals(ContactGate.RequestAction.PROCESS, ContactGate.forRequest(joined))
        assertFalse(ContactGate.reportable(joined))
    }

    @Test
    fun aRequestIsKeptQuietlyNeverRingsAndHoldsAnInvitationUntilAccepted() {
        val expected = mapOf(
            CommsWire.message("m1", 1L, "hi") to ContactGate.RequestAction.KEEP,
            CommsWire.mediaHeader("x1", 1L, "image/jpeg", 1, 1, 0, 10, 1, null, null) to ContactGate.RequestAction.KEEP,
            CommsWire.mediaChunk("x1", 0, "AAAA") to ContactGate.RequestAction.KEEP,
            CommsWire.voicemail("v1", 1L, "AAAA", 1000L) to ContactGate.RequestAction.KEEP,
            CommsWire.payment("p1", "tx", 1L, 5L, null) to ContactGate.RequestAction.KEEP,
            CommsWire.callOffer("c1", 1L, "v=0") to ContactGate.RequestAction.MISSED_CALL,
            CommsWire.callRinging("c1") to ContactGate.RequestAction.IGNORE,
            CommsWire.callAnswer("c1", "v=0") to ContactGate.RequestAction.IGNORE,
            CommsWire.callEnd("c1", CommsWire.END_CANCEL) to ContactGate.RequestAction.IGNORE,
            CommsWire.callReoffer("c1", "v=0") to ContactGate.RequestAction.IGNORE,
            CommsWire.groupCtrl("g1", CommsWire.OP_ADD) to ContactGate.RequestAction.HOLD,
            CommsWire.groupCtrl("g1", CommsWire.OP_LEAVE) to ContactGate.RequestAction.PROCESS,
            CommsWire.groupMsg("g1", "hi all", "gm1", 1L) to ContactGate.RequestAction.PROCESS,
            CommsWire.receipt(listOf("m0"), CommsWire.STATUS_READ) to ContactGate.RequestAction.IGNORE,
            CommsWire.resync() to ContactGate.RequestAction.IGNORE,
            CommsWire.timer("t1", 1L, 300) to ContactGate.RequestAction.PROCESS,
            CommsWire.moved(1L) to ContactGate.RequestAction.PROCESS,
            CommsWire.pass() to ContactGate.RequestAction.PROCESS,
            CommsWire.noise("xxxx") to ContactGate.RequestAction.PROCESS,
        )
        for ((payload, action) in expected) {
            val label = CommsWire.type(payload) + "/" + CommsWire.callKind(payload) + payload.optString(CommsWire.F_OP)
            assertEquals(label, action, ContactGate.forRequest(payload))
        }
    }

    private fun inbound(id: String, status: String, read: Boolean, kind: String = KIND_TEXT) =
        ChatMessage(id, "482913605", Direction.IN, "x", 1L, status, read, kind = kind)

    @Test
    fun acceptingOwesADeliveryReceiptForEachMessageNeverWhatWasReadAndNoneForTheRest() {
        val messages = listOf(
            inbound("read-1", "received", read = true),
            inbound("unread-1", "received", read = false),
            inbound("voicemail-1", STATUS_VOICEMAIL, read = false),
            inbound("payment-1", STATUS_PAYMENT, read = true),
            inbound("photo-1", "received", read = false, kind = KIND_MEDIA),
            // Never receipted, as in any conversation.
            inbound("call-1", STATUS_CALL, read = false),
            inbound("note-1", STATUS_SYSTEM, read = false, kind = KIND_NOTE),
            inbound("arriving-1", STATUS_RECEIVING, read = false, kind = KIND_MEDIA),
            ChatMessage("mine-1", "482913605", Direction.OUT, "x", 1L, "sent", read = true),
        )
        val owed = ContactGate.receiptsOnAccept(messages)
        // What was read while it was a request is not told: the card promised so.
        assertEquals(setOf(CommsWire.STATUS_DELIVERED), owed.keys)
        assertEquals(listOf("read-1", "unread-1", "voicemail-1", "payment-1", "photo-1"), owed[CommsWire.STATUS_DELIVERED])
        assertTrue(ContactGate.receiptsOnAccept(emptyList()).isEmpty())
    }

    // ── Reports ──────────────────────────────────────────────────────────

    @Test
    fun whatTheySentIsReportableAndWhatTheirPhoneDoesOnItsOwnIsNot() {
        for (p in listOf(
            CommsWire.message("m1", 1L, "hi"), CommsWire.mediaHeader("x1", 1L, "image/jpeg", 1, 1, 0, 10, 1, null, null),
            CommsWire.voicemail("v1", 1L, "AAAA", 1000L), CommsWire.payment("p1", "tx", 1L, 5L, null),
            CommsWire.callOffer("c1", 1L, "v=0"), CommsWire.groupMsg("g1", "hi", "gm1", 1L), CommsWire.groupCtrl("g1", CommsWire.OP_ADD),
            CommsWire.threat("g1", "t1", 1L, "CATCHER", "HIGH", 1.0, 2.0, "t", "d", 2L), CommsWire.panic("g1", "s1", 1L, null, null, null),
            CommsWire.timer("t1", 1L, 300)
        )) assertTrue(CommsWire.type(p), ContactGate.reportable(p))
        for (p in listOf(
            CommsWire.receipt(listOf("m0"), CommsWire.STATUS_READ), CommsWire.pass(), CommsWire.noise("xx"), CommsWire.resync(),
            CommsWire.mediaChunk("x1", 0, "AAAA"), CommsWire.callEnd("c1", CommsWire.END_CANCEL), CommsWire.moved(1L)
        )) assertFalse(CommsWire.type(p), ContactGate.reportable(p))
    }

    @Test
    fun aReportNamesTheReportedNumberAndNothingAboutWhoReports() {
        val hash = "a".repeat(64)
        val here = ContactGate.reportBody("482913605", null, hash, ContactGate.ReportReason.THREATS)
        assertEquals(setOf("number", "envelope", "reason"), here.keys().asSequence().toSet())
        assertEquals("482913605", here.getString("number"))
        assertEquals(hash, here.getString("envelope"))
        assertEquals("threats", here.getString("reason"))

        val there = ContactGate.reportBody("111222333", "https://relay.example.org", null, ContactGate.ReportReason.SPAM)
        assertEquals(setOf("number", "relay", "reason"), there.keys().asSequence().toSet())
        assertEquals("https://relay.example.org", there.getString("relay"))

        // Anything but an envelope's hash is left out rather than sent.
        val odd = ContactGate.reportBody("482913605", null, "not-a-hash", ContactGate.ReportReason.OTHER)
        assertFalse(odd.has("envelope"))
        assertFalse(ContactGate.reportBody("482913605", null, "A".repeat(64), ContactGate.ReportReason.OTHER).has("envelope"))
    }

    @Test
    fun aReportCarriesAFingerprintOfTheEnvelopeNeverItsHash() {
        val hash = "0123456789abcdef".repeat(4)
        val secret = ByteArray(32) { it.toByte() }
        val print = ContactGate.envelopeFingerprint(secret, hash)
        assertTrue(print != null && Regex("^[0-9a-f]{64}$").matches(print))
        // The person reported knows the hash of every envelope they sent: the report must not carry it.
        assertFalse(print == hash)
        // The same envelope reported twice from this phone gives the same fingerprint, so the relay counts it once.
        assertEquals(print, ContactGate.envelopeFingerprint(secret, hash))
        // Another phone's report of the same bytes, or this phone's of another envelope, cannot be matched to it.
        assertFalse(print == ContactGate.envelopeFingerprint(ByteArray(32) { 7 }, hash))
        assertFalse(print == ContactGate.envelopeFingerprint(secret, "f".repeat(64)))
        // Each report has its own key, so revealing one reveals no other.
        assertFalse(ContactGate.reportKey(secret, hash).contentEquals(ContactGate.reportKey(secret, "f".repeat(64))))
        // Only an envelope hash is fingerprinted, and what comes out is what reportBody sends.
        assertEquals(null, ContactGate.envelopeFingerprint(secret, "not-a-hash"))
        assertEquals(print, ContactGate.reportBody("482913605", null, print, ContactGate.ReportReason.THREATS).getString("envelope"))
    }

    @Test
    fun theReasonsAreTheWordsTheRelayAccepts() {
        // comms-worker/src/comms-logic.ts REPORT_REASONS lists the same words.
        assertEquals(
            listOf("harassment", "threats", "spam", "impersonation", "other"),
            ContactGate.ReportReason.entries.map { it.wire }
        )
    }
}
