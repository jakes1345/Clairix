package app.clairix.analysis

import app.clairix.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException

/**
 * The trust-store audit's result from what its two sources gave, what the Certificates page shows
 * for it, and the Phone check row: a source that could not be read is said so, and never shown as
 * a clean store or as "none added by hand".
 */
class CaAuditScannerTest {

    private fun root(cn: String, fingerprint: String) = CaAuditScanner.Root(fingerprint, "CN=$cn", "CN=Issuer", 0L, 1L)

    /** A root in the AOSP bundle (by its CN), one a carrier added, and one installed by hand. */
    private val gts = root("GTS Root R1", "01")
    private val vendor = root("Vendor Carrier Root", "02")
    private val mitm = root("mitmproxy", "03")

    private fun listing(user: List<CaAuditScanner.Root> = emptyList(), system: Set<String> = setOf("01", "02"), unreadable: Int = 0) =
        CaAuditScanner.StoreListing(user, system, unreadable)

    @Test
    fun bothSourcesReadWithNothingToShowIsTheOnlyCleanState() {
        val audit = CaAuditScanner.audit(Result.success(listing()), Result.success(listOf(gts)))
        assertTrue(audit.complete)
        assertTrue(audit.roots.isEmpty())
        assertNull(audit.error)
        assertEquals(CaPage.Clean, CaAuditScanner.page(audit))
        val fact = CaAuditScanner.fact(audit)
        assertEquals("None added by hand", fact.value)
        assertNull(fact.severity)
        assertNull(fact.detail)
    }

    @Test
    fun thePageReadsUntilTheAuditArrives() {
        assertEquals(CaPage.Reading, CaAuditScanner.page(null))
    }

    @Test
    fun aStoreThatCouldNotBeReadIsNeverCleanAndNeverNoneAddedByHand() {
        val audit = CaAuditScanner.audit(Result.failure(KeyStoreException("AndroidCAStore not found")), Result.success(listOf(gts)))
        assertFalse(audit.userStoreRead)
        assertTrue(audit.trustManagerRead)
        assertEquals("the phone's certificate store: KeyStoreException: AndroidCAStore not found", audit.error)

        val page = CaAuditScanner.page(audit) as CaPage.Findings
        assertEquals("Could not read the trust store (the phone's certificate store: KeyStoreException: AndroidCAStore not found)", page.failure)
        assertTrue(page.failureDetail!!, page.failureDetail!!.contains("User credentials"))
        assertTrue(page.roots.isEmpty())

        val fact = CaAuditScanner.fact(audit)
        assertEquals("Could not read the trust store", fact.value)
        assertNotEquals("None added by hand", fact.value)
        assertEquals(Severity.MEDIUM, fact.severity)
        assertTrue(fact.detail!!, fact.detail!!.contains("KeyStoreException") && fact.detail!!.contains("User credentials"))
        assertEquals(android.provider.Settings.ACTION_SECURITY_SETTINGS, fact.fixAction)
    }

    @Test
    fun aStoreThatListsNothingCountsAsUnread() {
        // A working phone always lists its system roots; an empty store has not been read.
        val audit = CaAuditScanner.audit(Result.success(CaAuditScanner.StoreListing(emptyList(), emptySet())), Result.success(listOf(gts)))
        assertFalse(audit.userStoreRead)
        assertEquals("the phone's certificate store listed nothing", audit.error)
        assertTrue(CaAuditScanner.page(audit) is CaPage.Findings)
        assertEquals(Severity.MEDIUM, CaAuditScanner.fact(audit).severity)

        val noRoots = CaAuditScanner.audit(Result.success(listing()), Result.success(emptyList()))
        assertFalse(noRoots.trustManagerRead)
        assertEquals("the phone listed no roots it accepts", noRoots.error)
        assertNotEquals(CaPage.Clean, CaAuditScanner.page(noRoots))
    }

    @Test
    fun withTheStoreUnreadAnAcceptedRootIsASystemRootNotOneFromOutside() {
        // The failure is no evidence of a modified trust manager: the trust manager takes system roots only.
        val audit = CaAuditScanner.audit(Result.failure(KeyStoreException("x")), Result.success(listOf(gts, vendor)))
        assertEquals(listOf("system"), audit.roots.map { it.source })
        assertEquals(Severity.MEDIUM, audit.roots.single().severity)
        assertEquals("Could not read the trust store", CaAuditScanner.fact(audit).value)
    }

    @Test
    fun aTrustManagerThatCouldNotBeReadStillShowsRootsInstalledByHand() {
        val audit = CaAuditScanner.audit(Result.success(listing(user = listOf(mitm), system = setOf("01"))), Result.failure(NoSuchAlgorithmException("X509")))
        assertTrue(audit.userStoreRead)
        assertFalse(audit.trustManagerRead)

        val page = CaAuditScanner.page(audit) as CaPage.Findings
        assertEquals("Could not read the trust store (the roots this phone accepts: NoSuchAlgorithmException: X509)", page.failure)
        assertTrue(page.failureDetail!!, page.failureDetail!!.endsWith("listed below."))
        assertEquals(listOf("user"), page.roots.map { it.source })
        assertEquals(Severity.HIGH, page.roots.single().severity)

        val fact = CaAuditScanner.fact(audit)
        assertEquals("1 added by hand", fact.value)
        assertEquals(Severity.HIGH, fact.severity)
        assertTrue(fact.detail!!, fact.detail!!.contains("could not be read"))

        // Nothing installed by hand, and that much was read: the row says so, and what was not read.
        val none = CaAuditScanner.audit(Result.success(listing()), Result.failure(NoSuchAlgorithmException("X509")))
        assertTrue((CaAuditScanner.page(none) as CaPage.Findings).failureDetail!!.endsWith("there are none."))
        val noneFact = CaAuditScanner.fact(none)
        assertEquals("None added by hand", noneFact.value)
        assertNull(noneFact.severity)
        assertTrue(noneFact.detail!!, noneFact.detail!!.contains("could not be read"))
    }

    @Test
    fun bothSourcesFailingNameBothReasons() {
        val audit = CaAuditScanner.audit(Result.failure(KeyStoreException("a")), Result.failure(NoSuchAlgorithmException("b")))
        assertFalse(audit.userStoreRead || audit.trustManagerRead)
        assertEquals(
            "Could not read the trust store (the phone's certificate store: KeyStoreException: a; " +
                "the roots this phone accepts: NoSuchAlgorithmException: b)",
            (CaAuditScanner.page(audit) as CaPage.Findings).failure
        )
        assertEquals(Severity.MEDIUM, CaAuditScanner.fact(audit).severity)
    }

    @Test
    fun aRootTrustedFromOutsideBothStoresIsHigh() {
        val audit = CaAuditScanner.audit(Result.success(listing(system = setOf("01"))), Result.success(listOf(gts, vendor)))
        assertTrue(audit.complete)
        assertEquals(listOf("unknown"), audit.roots.map { it.source })
        assertEquals(Severity.HIGH, audit.roots.single().severity)
        val page = CaAuditScanner.page(audit) as CaPage.Findings
        assertNull(page.failure)
        assertEquals("1 from outside the stores", CaAuditScanner.fact(audit).value)
    }

    @Test
    fun aCertificateInstalledByHandThatWouldNotOpenStillCounts() {
        val audit = CaAuditScanner.audit(Result.success(listing(unreadable = 1)), Result.success(listOf(gts)))
        assertTrue(audit.complete)
        assertEquals(1, audit.unreadableUser)
        val page = CaAuditScanner.page(audit) as CaPage.Findings
        assertEquals(1, page.unreadableUser)
        assertNull(page.failure)
        val fact = CaAuditScanner.fact(audit)
        assertEquals("1 added by hand", fact.value)
        assertEquals(Severity.HIGH, fact.severity)
        assertTrue(fact.detail!!, fact.detail!!.contains("would not open"))
    }

    @Test
    fun rootsInstalledByHandComeFirstAndAreNotListedTwice() {
        // A debug build's config may trust user roots too; the trust manager's copy is the same root.
        val audit = CaAuditScanner.audit(
            Result.success(listing(user = listOf(mitm), system = setOf("01", "02"))),
            Result.success(listOf(gts, vendor, mitm))
        )
        assertEquals(listOf("03", "02"), audit.roots.map { it.fingerprint })
        assertEquals(listOf("user", "system"), audit.roots.map { it.source })
    }
}
