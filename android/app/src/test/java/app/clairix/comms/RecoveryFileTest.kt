package app.clairix.comms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's half of a recovery file (what is and is not in it) and the plan a
 * restore follows from what the relay says. The identity half, its format
 * and its encryption are tested in comms-crypto (src/recovery.rs).
 */
class RecoveryFileTest {

    private val relay = "https://relay.example.org"
    private val passKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    private val friend = Contact(
        number = "482913605", name = "Robin", ed25519 = "ED-ROBIN", curve25519 = "CURVE-ROBIN", sealing = "SEAL-ROBIN",
        signature = "SIG-ROBIN", verified = true, addedTs = 1_700_000_000_000L
    )
    private val changed = friend.copy(number = "511222333", name = "Alex", ed25519 = "ED-ALEX", curve25519 = "CURVE-ALEX", verified = false, keyChanged = true, relay = "https://other.example.net")
    private val request = friend.copy(number = "622333444", name = "", ed25519 = "ED-REQ", curve25519 = "CURVE-REQ", verified = false, request = true)
    private val blockedRow = friend.copy(number = "733444555", name = "Sam", ed25519 = "ED-SAM", curve25519 = "CURVE-SAM", verified = false, blocked = true)
    private val group = Group(id = "gid-1", name = "Shelter staff", createdBy = "482913605", createdTs = 1_700_000_100_000L, myRole = OrgRole.ADMIN, rosterVersion = 4, rosterSeen = 5)
    private val members = listOf(
        GroupMember("gid-1", "482913605", "Robin", OrgRole.ADMIN, "ED-ROBIN", "482913605", 1_700_000_100_000L, rv = 4),
        GroupMember("gid-1", "900100200", "Me", OrgRole.MEMBER, "ED-ME", "482913605", 1_700_000_200_000L, rv = 3)
    )
    private val blocked = ContactGate.Blocked(id = 9L, ed25519 = "ED-SAM", curve25519 = "CURVE-SAM", number = "733444555", relay = null, name = "Sam", ts = 1_700_000_300_000L)

    private fun record() = RecoveryRecord(
        savedAt = 1_762_000_000_000L,
        relayUrl = relay,
        number = "900100200",
        name = "Me",
        listed = false,
        contacts = listOf(friend, changed, request, blockedRow),
        contactPasses = mapOf("482913605" to (passKey to 1_700_000_400_000L)),
        groups = listOf(group),
        members = members,
        blocked = listOf(blocked),
        timers = mapOf("482913605" to (3600 to 1_700_000_500_000L), "gid-1" to (86_400 to 1_700_000_600_000L))
    )

    // ── What is in it ────────────────────────────────────────────────────

    @Test
    fun aRecordComesBackWhole() {
        val back = RecoveryRecord.decode(record().encode())
        // Blocked rows and the blocked list come back as data; the row id is the store's to give.
        assertEquals(record().copy(blocked = listOf(blocked.copy(id = 0L))), back)
        // Verification and a key change still unreviewed are kept as they were.
        assertTrue(back.contacts.single { it.number == friend.number }.verified)
        assertTrue(back.contacts.single { it.number == changed.number }.keyChanged)
        assertTrue(back.contacts.single { it.number == request.number }.request)
        assertTrue(back.contacts.single { it.number == blockedRow.number }.blocked)
        assertEquals(OrgRole.ADMIN, back.groups.single().myRole)
        assertEquals(listOf(4L, 3L), back.members.map { it.rv })
    }

    @Test
    fun itHoldsNoMessageNoSessionNoOwnPassAndNoRelayCredential() {
        val json = JSONObject(String(record().encode(), Charsets.UTF_8))
        assertEquals(
            setOf("v", "savedAt", "relay", "number", "name", "listed", "contacts", "groups", "blocked", "timers"),
            json.keys().asSequence().toSet()
        )
        val contact = json.getJSONArray("contacts").getJSONObject(0)
        assertEquals(
            setOf("number", "name", "ed25519", "curve25519", "sealing", "signature", "verified", "keyChanged", "added", "isBlocked", "request", "pass"),
            contact.keys().asSequence().toSet()
        )
        val text = json.toString()
        for (absent in listOf("session", "message", "body", "pickle", "oneTime", "otk", "token", "claim", "prekey", "outbox", "secret", "invite")) {
            assertFalse("the record mentions $absent", text.contains(absent, ignoreCase = true))
        }
    }

    @Test
    fun aRecordFromANewerAppOrWithoutANumberIsRefusedNotHalfRead() {
        val newer = JSONObject(String(record().encode(), Charsets.UTF_8)).put("v", RecoveryRecord.VERSION + 1)
        assertThrows(IllegalArgumentException::class.java) { RecoveryRecord.decode(newer.toString().toByteArray()) }
        val noNumber = JSONObject(String(record().encode(), Charsets.UTF_8)).put("number", "12")
        assertThrows(IllegalArgumentException::class.java) { RecoveryRecord.decode(noNumber.toString().toByteArray()) }
        val plainHttp = JSONObject(String(record().encode(), Charsets.UTF_8)).put("relay", "http://relay.example.org")
        assertThrows(IllegalArgumentException::class.java) { RecoveryRecord.decode(plainHttp.toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { RecoveryRecord.decode("not json".toByteArray()) }
        val keyless = JSONObject(String(record().encode(), Charsets.UTF_8))
        keyless.getJSONArray("contacts").getJSONObject(0).remove("ed25519")
        assertThrows(IllegalArgumentException::class.java) { RecoveryRecord.decode(keyless.toString().toByteArray()) }
    }

    @Test
    fun aMalformedContactPassIsDroppedNotKept() {
        val json = JSONObject(String(record().encode(), Charsets.UTF_8))
        json.getJSONArray("contacts").getJSONObject(0).put("pass", JSONObject().put("key", "c2hvcnQ=").put("created", 1L))
        assertTrue(RecoveryRecord.decode(json.toString().toByteArray()).contactPasses.isEmpty())
    }

    // ── The plan ─────────────────────────────────────────────────────────

    @Test
    fun theOldMailboxStillThereMeansTheSameNumber() {
        assertEquals(RestorePlan.Resume(relay, "900100200"), RestorePlan.of(record(), RelayProbe.Found))
        // An invite brought along changes nothing while the old mailbox is there.
        assertEquals(RestorePlan.Resume(relay, "900100200"), RestorePlan.of(record(), RelayProbe.Found, "https://elsewhere.example.com"))
    }

    @Test
    fun aGoneMailboxMeansRegisteringTheSameKeysAgain() {
        assertEquals(RestorePlan.Reregister(relay, "900100200"), RestorePlan.of(record(), RelayProbe.Gone))
        // With an invite to another relay, there.
        assertEquals(
            RestorePlan.Reregister("https://elsewhere.example.com", "900100200"),
            RestorePlan.of(record(), RelayProbe.Gone, "https://elsewhere.example.com")
        )
    }

    @Test
    fun anUnreachableRelayChangesNothingUnlessAnInviteNamesAnother() {
        val plan = RestorePlan.of(record(), RelayProbe.Unreachable("timeout"))
        assertTrue(plan is RestorePlan.Retry)
        assertTrue((plan as RestorePlan.Retry).reason.contains("relay.example.org"))
        // An invite to the same relay is no way round it being down.
        assertTrue(RestorePlan.of(record(), RelayProbe.Unreachable("timeout"), "$relay/") is RestorePlan.Retry)
        assertEquals(
            RestorePlan.Reregister("https://elsewhere.example.com", "900100200"),
            RestorePlan.of(record(), RelayProbe.Unreachable("timeout"), "https://elsewhere.example.com")
        )
    }

    @Test
    fun aSuspendedNumberIsNotTakenForAGoneOne() {
        // The relay answers a signed request from a suspended account with 403 and its operator's words.
        val said = "This Clairix number is suspended on this relay by its operator. To appeal, write to abuse@relay.example.org."
        assertEquals(RelayProbe.Suspended(said), RelayProbe.fromRefusal(403, said))
        // Nothing is registered again on that relay, where the keys still belong to the suspended number.
        assertEquals(RestorePlan.Suspended(relay, said), RestorePlan.of(record(), RelayProbe.Suspended(said)))
        assertEquals(RestorePlan.Suspended(relay, said), RestorePlan.of(record(), RelayProbe.Suspended(said), "$relay/"))
        // An invite to another relay takes the same keys under a new number.
        assertEquals(
            RestorePlan.Reregister("https://elsewhere.example.com", "900100200"),
            RestorePlan.of(record(), RelayProbe.Suspended(said), "https://elsewhere.example.com")
        )
    }

    @Test
    fun onlyTheRelaysNoMailboxAnswersCountAsGone() {
        assertEquals(RelayProbe.Gone, RelayProbe.fromRefusal(401, "Signature rejected"))
        assertEquals(RelayProbe.Gone, RelayProbe.fromRefusal(404, "Not found"))
        assertFalse(RelayProbe.fromRefusal(403, "suspended") == RelayProbe.Gone)
        // A relay in trouble, a rate limit or no answer at all is not a reason to give up the number.
        for (code in listOf(0, 429, 500, 502, 503)) {
            assertTrue("$code", RelayProbe.fromRefusal(code, "x") is RelayProbe.Unreachable)
        }
    }
}
