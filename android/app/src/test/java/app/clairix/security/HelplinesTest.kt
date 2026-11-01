package app.clairix.security

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The Get help page's helplines: which country is shown (the SIM's, then the
 * phone's region, then none), that nothing unchecked gets through the parser,
 * and that the bundled list holds what was verified.
 */
class HelplinesTest {

    private fun line(name: String, phone: String = "0800 000 000", source: String? = "https://example.org/", verified: String? = "2026-11-01") =
        JSONObject().apply {
            put("name", name)
            put("phone", phone)
            put("hours", "24 hours")
            source?.let { put("source", it) }
            verified?.let { put("verified", it) }
        }

    private fun country(name: String, vararg lines: JSONObject) =
        JSONObject().put("name", name).put("lines", org.json.JSONArray().apply { lines.forEach { put(it) } })

    private val book = Helplines.parse(
        JSONObject().put(
            "countries",
            JSONObject()
                .put("US", country("United States", line("US line")))
                .put("GB", country("United Kingdom", line("GB line")))
                .put("IE", country("Ireland", line("IE line")))
        ).toString()
    )

    // ── Which country ─────────────────────────────────────────────────────────

    @Test
    fun theSimCountryIsShownWhenTheListHasIt() {
        val c = Helplines.choose(book, simCountry = "ie", regionCountry = "US")
        assertEquals("IE", c.country?.code)
        assertEquals(Helplines.Match.SIM, c.match)
    }

    @Test
    fun theRegionIsUsedWhenTheSimCountryIsNotListed() {
        val c = Helplines.choose(book, simCountry = "pt", regionCountry = "US")
        assertEquals("US", c.country?.code)
        assertEquals(Helplines.Match.REGION, c.match)
    }

    @Test
    fun theRegionIsUsedWithNoSim() {
        val c = Helplines.choose(book, simCountry = "", regionCountry = "gb")
        assertEquals("GB", c.country?.code)
        assertEquals(Helplines.Match.REGION, c.match)
        assertEquals("GB", Helplines.choose(book, simCountry = null, regionCountry = "GB").country?.code)
    }

    @Test
    fun noListedCountryShowsNoneAndNamesWhatThePhoneReported() {
        val c = Helplines.choose(book, simCountry = "pt", regionCountry = "BR")
        assertNull(c.country)
        assertEquals(Helplines.Match.NONE, c.match)
        assertEquals("the SIM's country is the one named", "PT", c.unmatched)
        assertEquals("BR", Helplines.choose(book, simCountry = null, regionCountry = "br").unmatched)
    }

    @Test
    fun aPhoneThatReportsNoCountryShowsNoneWithNothingToName() {
        val c = Helplines.choose(book, simCountry = null, regionCountry = "")
        assertNull(c.country)
        assertEquals(Helplines.Match.NONE, c.match)
        assertNull(c.unmatched)
    }

    @Test
    fun ukIsReadAsGbAndMalformedCodesAreIgnored() {
        assertEquals("GB", Helplines.normalize("uk"))
        assertEquals("GB", Helplines.normalize(" gb "))
        assertNull(Helplines.normalize("USA"))
        assertNull(Helplines.normalize("1"))
        assertNull(Helplines.normalize("4F"))
        assertNull(Helplines.normalize(""))
        assertNull(Helplines.normalize(null))
        assertEquals("GB", Helplines.choose(book, simCountry = "UK", regionCountry = "US").country?.code)
        // A malformed SIM code falls through to the region rather than matching nothing.
        assertEquals(Helplines.Match.REGION, Helplines.choose(book, simCountry = "USA", regionCountry = "IE").match)
    }

    // ── Nothing unchecked gets through ───────────────────────────────────────

    @Test
    fun aLineWithoutAnHttpsSourceOrAVerifiedDateIsDropped() {
        val parsed = Helplines.parse(
            JSONObject().put(
                "countries",
                JSONObject().put(
                    "XX",
                    country(
                        "Testland",
                        line("good"),
                        line("no source", source = null),
                        line("plain http", source = "http://example.org/"),
                        line("no date", verified = null),
                        line("bad date", verified = "1 Nov 2026"),
                        line("no number", phone = "call us")
                    )
                )
            ).toString()
        )
        assertEquals(listOf("good"), parsed.countries.getValue("XX").lines.map { it.name })
    }

    @Test
    fun aMalformedTextOrChatOptionIsDroppedAndTheLineKept() {
        val l = line("line")
            .put("text", JSONObject().put("label", "Text us").put("to", "text-us"))
            .put(
                "chat",
                org.json.JSONArray()
                    .put(JSONObject().put("label", "Chat").put("url", "http://example.org/chat"))
                    .put(JSONObject().put("label", "Chat safely").put("url", "https://example.org/chat"))
            )
        val parsed = Helplines.parse(JSONObject().put("countries", JSONObject().put("XX", country("Testland", l))).toString())
        val kept = parsed.countries.getValue("XX").lines.single()
        assertNull(kept.text)
        assertEquals(listOf("https://example.org/chat"), kept.chat.map { it.url })
    }

    @Test
    fun aCountryWithNothingCheckedIsDroppedAndOneWithOnlyAnEmergencyNumberIsKept() {
        val emergency = JSONObject().put("number", "112").put("source", "https://example.org/112").put("verified", "2026-10-30")
        val parsed = Helplines.parse(
            JSONObject().put(
                "countries",
                JSONObject()
                    .put("XX", country("Nothing checked", line("unsourced", source = null)))
                    .put("YY", country("Emergency only").put("emergency", emergency))
                    .put("ZZZ", country("Not a code", line("fine")))
            ).toString()
        )
        assertEquals(setOf("YY"), parsed.countries.keys)
        assertEquals("112", parsed.countries.getValue("YY").emergency?.number)
    }

    @Test
    fun lastVerifiedIsTheOldestCheckBehindACountry() {
        val emergency = JSONObject().put("number", "112").put("source", "https://example.org/112").put("verified", "2026-10-30")
        val parsed = Helplines.parse(
            JSONObject().put(
                "countries",
                JSONObject().put("XX", country("Testland", line("a", verified = "2026-11-01"), line("b", verified = "2026-10-31")).put("emergency", emergency))
            ).toString()
        )
        assertEquals(LocalDate.of(2026, 10, 30), Helplines.lastVerified(parsed.countries.getValue("XX")))
        assertEquals("30 October 2026", Helplines.formatDate(LocalDate.of(2026, 10, 30)))
        assertEquals("1 November 2026", Helplines.formatDate(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun aPrintedNumberIsDialledAsDigitsKeepingAShortcutsHash() {
        assertEquals("18007997233", Helplines.dialString("1-800-799-7233"))
        assertEquals("08088010800", Helplines.dialString("0808 80 10 800"))
        assertEquals("#7233", Helplines.dialString("#7233"))
        assertEquals("+448082000247", Helplines.dialString("+44 808 2000 247"))
    }

    // ── The bundled list ─────────────────────────────────────────────────────

    private fun bundled(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/assets/helplines.json"), File(it, "android/app/src/main/assets/helplines.json")) }
            .first { it.isFile }

    @Test
    fun theBundledListParsesWithoutDroppingAnything() {
        val raw = bundled().readText()
        val parsed = Helplines.parse(raw)
        val countries = JSONObject(raw).getJSONObject("countries")
        var rawLines = 0
        for (key in countries.keys()) rawLines += countries.getJSONObject(key).getJSONArray("lines").length()
        assertEquals("every country in the file is shown", countries.length(), parsed.countries.size)
        assertEquals("every line in the file passed the checks", rawLines, parsed.countries.values.sumOf { it.lines.size })
        val today = LocalDate.now()
        for (c in parsed.countries.values) {
            assertEquals("${c.code} is keyed by its own code", c.code, Helplines.normalize(c.code))
            val e = c.emergency
            assertNotNull("${c.code} has an emergency number", e)
            assertTrue("${c.code}'s emergency number cites its source", e!!.source.startsWith("https://"))
            assertFalse("${c.code}'s emergency check is not in the future", e.verified.isAfter(today))
            assertTrue("${c.code} has a line", c.lines.isNotEmpty())
            for (l in c.lines) {
                assertTrue("${l.name} cites its source", l.source.startsWith("https://"))
                assertFalse("${l.name} was checked no later than today", l.verified.isAfter(today))
                assertTrue("${l.name} is dialable", Helplines.dialString(l.phone).isNotEmpty())
            }
        }
    }

    @Test
    fun theBundledListHoldsTheVerifiedNumbers() {
        val book = Helplines.parse(bundled().readText())
        fun phones(code: String) = book.countries.getValue(code).lines.map { it.phone }

        val us = book.countries.getValue("US").lines.single { it.phone == "1-800-799-7233" }
        assertEquals("88788", us.text?.to)
        assertEquals("START", us.text?.body)
        assertEquals("911", book.countries.getValue("US").emergency?.number)

        val england = book.countries.getValue("GB").lines.single { it.phone == "0808 2000 247" }
        assertEquals("England", england.area)
        assertTrue(phones("GB").containsAll(listOf("0800 027 1234", "0808 80 10 800", "0808 802 1414")))
        assertEquals("999", book.countries.getValue("GB").emergency?.number)

        assertTrue("1800 341 900" in phones("IE"))
        assertTrue("1800 737 732" in phones("AU"))
        assertTrue("0800 456 450" in phones("NZ"))

        val ontario = book.countries.getValue("CA").lines.single { it.phone == "1-866-863-0511" }
        assertEquals("Ontario only", ontario.area)
        assertTrue("every Canadian line says where it serves", book.countries.getValue("CA").lines.all { it.area?.endsWith(" only") == true })

        // 116 016 where the operator's own site says it answers, and nowhere it was not confirmed.
        for (code in listOf("DE", "CZ", "FI", "SE")) assertTrue("$code answers 116 016", "116 016" in phones(code))
        for (code in book.countries.keys - setOf("DE", "CZ", "FI", "SE")) assertFalse("$code: 116 016 not confirmed", "116 016" in phones(code))
    }
}
