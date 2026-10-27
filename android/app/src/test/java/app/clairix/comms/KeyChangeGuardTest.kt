package app.clairix.comms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What happens to payloads from a contact whose keys changed, until the owner looks. */
class KeyChangeGuardTest {

    private val trusted = Contact(
        number = "482913605", name = "Ana", ed25519 = "ED-OLD", curve25519 = "CURVE-OLD",
        sealing = "SEAL", signature = "SIG", verified = true, addedTs = 1L
    )
    /** The same number after a first message arrived from new keys. */
    private val changed = trusted.copy(ed25519 = "ED-NEW", curve25519 = "CURVE-NEW", verified = false, keyChanged = true)

    private val everyType: List<JSONObject> = listOf(
        CommsWire.message("m1", 1L, "hi"),
        CommsWire.receipt(listOf("m0"), CommsWire.STATUS_READ),
        CommsWire.resync(),
        CommsWire.callOffer("c1", 1L, "v=0"),
        CommsWire.payment("p1", "tx", 1L, 5L, null),
        CommsWire.voicemail("v1", 1L, "AAAA", 1000L),
        CommsWire.mediaHeader("x1", 1L, "image/jpeg", 1, 1, 0, 10, 1, null, null),
        CommsWire.mediaChunk("x1", 0, "AAAA"),
        CommsWire.groupMsg("g1", "hi all", "gm1", 1L),
        CommsWire.groupCtrl("g1", CommsWire.OP_ADD),
        CommsWire.groupCtrl("g1", CommsWire.OP_REMOVE, mapOf(CommsWire.F_MEMBER to "123456789")),
        CommsWire.threat("g1", "t1", 1L, "CATCHER", "HIGH", 1.0, 2.0, "t", "d", 2L),
        CommsWire.panic("g1", "s1", 1L, null, null, null),
        CommsWire.moved(1L),
    )

    @Test
    fun everythingFromTrustedKeysIsProcessed() {
        for (p in everyType) assertEquals(CommsWire.type(p), KeyChangeGuard.Action.PROCESS, KeyChangeGuard.inbound(trusted, p))
    }

    @Test
    fun contentFromChangedKeysIsHeldForReview() {
        for (p in everyType.filter { CommsWire.type(it) in setOf(
            CommsWire.T_MSG, CommsWire.T_PAYMENT, CommsWire.T_VOICEMAIL, CommsWire.T_MEDIA, CommsWire.T_MEDIA_CHUNK,
            CommsWire.T_GROUP_MSG, CommsWire.T_THREAT, CommsWire.T_PANIC,
            // A move from new keys waits too: following it before the owner looks
            // would send their messages wherever those keys say.
            CommsWire.T_MOVED
        ) }) {
            assertEquals(CommsWire.type(p), KeyChangeGuard.Action.QUARANTINE, KeyChangeGuard.inbound(changed, p))
        }
    }

    @Test
    fun groupControlFromChangedKeysIsNeverApplied() {
        for (op in listOf(CommsWire.OP_ADD, CommsWire.OP_JOIN, CommsWire.OP_REMOVE, CommsWire.OP_LEAVE, CommsWire.OP_ROSTER)) {
            assertEquals(op, KeyChangeGuard.Action.DROP, KeyChangeGuard.inbound(changed, CommsWire.groupCtrl("g1", op)))
        }
        assertFalse(KeyChangeGuard.mayApplyGroupControl(changed, null))
        assertFalse(KeyChangeGuard.mayApplyGroupControl(changed, "ED-NEW"))
        // Even once the owner accepted the new keys, a roster that knew the old
        // ones does not take orders from the new ones until they are verified.
        val accepted = changed.copy(keyChanged = false)
        assertFalse(KeyChangeGuard.mayApplyGroupControl(accepted, "ED-OLD"))
        assertTrue(KeyChangeGuard.mayApplyGroupControl(accepted, "ED-NEW"))
        assertTrue(KeyChangeGuard.mayApplyGroupControl(trusted, "ED-OLD"))
    }

    @Test
    fun withNoRosterKeyOnlyAVerifiedContactMayChangeTheGroup() {
        // The roster recorded no key for them: only the owner's verification vouches.
        assertTrue(KeyChangeGuard.mayApplyGroupControl(trusted, ""))
        assertTrue(KeyChangeGuard.mayApplyGroupControl(trusted, null))
        val unverified = trusted.copy(verified = false)
        assertFalse(KeyChangeGuard.mayApplyGroupControl(unverified, ""))
        assertFalse(KeyChangeGuard.mayApplyGroupControl(unverified, null))
        // With a recorded key, the key decides.
        assertTrue(KeyChangeGuard.mayApplyGroupControl(unverified, "ED-OLD"))
        assertFalse(KeyChangeGuard.mayApplyGroupControl(unverified, "ED-OTHER"))
    }

    @Test
    fun aKeyChangeIsAnUnreadRowAndTheFirstHeldItemAlerts() {
        val row = KeyChangeGuard.systemMessage("row-1", changed, "Ana", 42L)
        assertEquals(STATUS_SYSTEM, row.status)
        assertEquals(Direction.IN, row.direction)
        assertEquals(changed.number, row.peer)
        assertFalse("the key-change row must count as unread", row.read)
        assertEquals("Safety number with Ana changed", row.body)
        assertTrue(STATUS_SYSTEM in TYPE_STATUSES)
        // The first held item notifies with an alert; later ones update it quietly.
        assertTrue(KeyChangeGuard.alertsOnHold(0))
        assertFalse(KeyChangeGuard.alertsOnHold(1))
        assertFalse(KeyChangeGuard.alertsOnHold(7))
        assertEquals("Ana's safety number changed — 1 item held, tap to review", KeyChangeGuard.heldNotice("Ana", 1))
        assertEquals("Ana's safety number changed — 3 items held, tap to review", KeyChangeGuard.heldNotice("Ana", 3))
    }

    @Test
    fun resyncsAndReceiptsFromChangedKeysAreDropped() {
        assertEquals(KeyChangeGuard.Action.DROP, KeyChangeGuard.inbound(changed, CommsWire.resync()))
        assertEquals(KeyChangeGuard.Action.DROP, KeyChangeGuard.inbound(changed, CommsWire.receipt(listOf("m"), CommsWire.STATUS_READ)))
    }

    @Test
    fun callsGoToTheCallManagerWhichRefusesToRing() {
        assertEquals(KeyChangeGuard.Action.PROCESS, KeyChangeGuard.inbound(changed, CommsWire.callOffer("c", 1L, "v=0")))
    }

    @Test
    fun heldPayloadsAreReleasedOnlyToTheKeysTheOwnerAccepted() {
        val msg = CommsWire.message("m1", 1L, "hi")
        // Still unreviewed: nothing comes out.
        assertFalse(KeyChangeGuard.releasable(changed, "CURVE-NEW", msg))
        val accepted = changed.copy(keyChanged = false)
        assertTrue(KeyChangeGuard.releasable(accepted, "CURVE-NEW", msg))
        // Held from yet other keys (they changed twice): not released to these.
        assertFalse(KeyChangeGuard.releasable(accepted, "CURVE-OTHER", msg))
        // A held row of a never-held type is not trusted either.
        assertFalse(KeyChangeGuard.releasable(accepted, "CURVE-NEW", CommsWire.groupCtrl("g1", CommsWire.OP_ADD)))
    }

    @Test
    fun theSystemLineNamesTheContact() {
        assertEquals("Safety number with Ana changed", KeyChangeGuard.systemLine("Ana"))
    }
}
