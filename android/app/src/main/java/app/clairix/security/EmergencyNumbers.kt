package app.clairix.security

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The number the SOS sheet's "Call" button puts in the phone app: the emergency number
 * of the country the phone is in. The country comes from the mobile network the phone
 * is on (TelephonyManager.getNetworkCountryIso), else from the SIM card
 * (getSimCountryIso); neither needs a permission. Where the network's country is
 * known, the SIM's is not used: a SIM from home says nothing about where the phone is.
 *
 * When neither is known, or the country is not in [TABLE], the button offers 112 and
 * says why: GSM and later mobile networks route 112 to the local emergency service in
 * most countries, and the 3GPP standard (TS 22.101, section 10) has every handset treat
 * 112 and 911 as emergency numbers, with or without a SIM.
 *
 * Sources, checked 2026-11-01:
 * - Wikipedia, "List of emergency telephone numbers"
 *   (https://en.wikipedia.org/wiki/List_of_emergency_telephone_numbers), which cites
 *   each country's authority and the ITU's list of emergency numbers (ITU Operational
 *   Bulletin annex, "List of Emergency Numbers").
 * - The European Commission on 112 in every EU member state
 *   (https://digital-strategy.ec.europa.eu/en/policies/112); the European Electronic
 *   Communications Code, Directive (EU) 2018/1972, article 109.
 *
 * Where a country has separate services, the number here is the one for the police
 * (an SOS is about someone's safety), and [Entry.note] names the others. A country the
 * sources disagree on, or where the number differs by region, is left out and gets 112.
 */
object EmergencyNumbers {

    /** The number offered when the country is unknown or not in [TABLE]. */
    const val FALLBACK = "112"

    /** One country's number, and the other services' numbers where they differ. */
    data class Entry(val number: String, val note: String? = null)

    /** Where the country behind [Choice.number] came from. */
    enum class Basis {
        /** The mobile network the phone is on: where the phone is. */
        NETWORK,
        /** The SIM card, the phone being on no mobile network now: the SIM's home country. */
        SIM,
        /** A country was found but the table has no entry for it: [FALLBACK]. */
        NOT_IN_TABLE,
        /** Neither the network nor the SIM gave a country: [FALLBACK]. */
        UNKNOWN
    }

    /** The number to offer, the country it is for (ISO 3166 code) when known, and how that was decided. */
    data class Choice(val number: String, val country: String?, val basis: Basis, val note: String? = null)

    private val EU_112 = listOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
        "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"
    )

    /** ISO 3166 code to number. Keep in step with the sources above; add only what they agree on. */
    val TABLE: Map<String, Entry> = buildMap {
        // EU: 112 reaches every emergency service in every member state (EECC article 109).
        EU_112.forEach { put(it, Entry("112")) }
        put("IE", Entry("112", "999 works as well"))
        put("DE", Entry("112", "Police also on 110"))
        put("FR", Entry("112", "Police also on 17, ambulance on 15"))
        put("AT", Entry("112", "Police also on 133"))
        put("BE", Entry("112", "Police also on 101"))
        put("ES", Entry("112", "National police also on 091"))
        put("PL", Entry("112", "Police also on 997"))
        put("CZ", Entry("112", "Police also on 158"))
        put("GR", Entry("112", "Police also on 100"))
        // Elsewhere in Europe.
        put("GB", Entry("999", "112 works as well"))
        listOf("IS", "NO", "CH", "LI", "AD", "MC", "UA", "MD", "GE", "RU", "TR", "ME", "MK", "KZ", "AZ")
            .forEach { put(it, Entry("112")) }
        // The Americas.
        listOf("US", "CA", "MX", "PR", "VI", "GU", "AS", "MP", "PA", "CR", "SV", "HN", "DO", "UY", "PY", "EC", "VE")
            .forEach { put(it, Entry("911")) }
        put("BR", Entry("190", "Police; ambulance 192, fire 193"))
        put("CL", Entry("133", "Police; ambulance 131, fire 132"))
        put("CO", Entry("123"))
        put("PE", Entry("105", "Police; fire 116"))
        put("BO", Entry("110", "Police; ambulance 118, fire 119"))
        put("JM", Entry("119", "Police; fire and ambulance 110"))
        // Asia and the Middle East.
        put("JP", Entry("110", "Police; fire and ambulance 119"))
        put("CN", Entry("110", "Police; ambulance 120, fire 119"))
        put("TW", Entry("110", "Police; fire and ambulance 119"))
        put("KR", Entry("112", "Police; fire and ambulance 119"))
        put("HK", Entry("999"))
        put("IN", Entry("112"))
        put("ID", Entry("112", "Police also on 110"))
        put("PH", Entry("911"))
        put("MY", Entry("999"))
        put("SG", Entry("999", "Police; ambulance and fire 995"))
        put("BD", Entry("999"))
        put("PK", Entry("15", "Police; rescue 1122 in most provinces"))
        put("LK", Entry("119", "Police"))
        put("NP", Entry("100", "Police; ambulance 102"))
        put("TH", Entry("191", "Police; ambulance 1669"))
        put("VN", Entry("113", "Police; ambulance 115, fire 114"))
        put("IL", Entry("100", "Police; ambulance 101, fire 102"))
        put("JO", Entry("911"))
        put("AE", Entry("999", "Police; ambulance 998, fire 997"))
        put("QA", Entry("999"))
        put("BH", Entry("999"))
        put("KW", Entry("112"))
        // Africa.
        put("ZA", Entry("10111", "Police; 112 from a mobile phone"))
        put("NG", Entry("112"))
        put("KE", Entry("999", "112 works as well"))
        put("EG", Entry("122", "Police; ambulance 123"))
        put("MA", Entry("19", "Police; ambulance 15"))
        // Oceania.
        put("AU", Entry("000", "112 works from a mobile phone"))
        put("NZ", Entry("111"))
    }

    /**
     * The number for a phone whose mobile network reports [networkIso] and whose SIM
     * reports [simIso] (either blank or null when unknown). The network wins when it
     * names a country, found in the table or not.
     */
    fun choose(networkIso: String?, simIso: String?): Choice {
        val network = Helplines.normalize(networkIso)
        val sim = Helplines.normalize(simIso)
        val (country, basis) = when {
            network != null -> network to Basis.NETWORK
            sim != null -> sim to Basis.SIM
            else -> return Choice(FALLBACK, null, Basis.UNKNOWN)
        }
        val entry = TABLE[country] ?: return Choice(FALLBACK, country, Basis.NOT_IN_TABLE)
        return Choice(entry.number, country, basis, entry.note)
    }

    /** [choose] for this phone, reading the network's and the SIM's country; no permission is needed. */
    fun local(context: Context): Choice {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        val network = runCatching { tm?.networkCountryIso }.getOrNull()
        val sim = runCatching { tm?.simCountryIso }.getOrNull()
        return choose(network, sim)
    }

    /** One sentence saying which number the button dials and why, as Settings and the SOS sheet show it. */
    fun explain(choice: Choice): String {
        val name = choice.country?.let(::countryName)
        val others = choice.note?.let { " $it." } ?: ""
        return when (choice.basis) {
            Basis.NETWORK -> "${choice.number} is the emergency number for $name, the country of the mobile network the phone is on.$others"
            Basis.SIM -> "${choice.number} is the emergency number for $name, the country of the SIM card: the phone is on no mobile network now, so where it is cannot be read.$others"
            Basis.NOT_IN_TABLE -> "Clairix has no emergency number listed for $name, so the button offers 112: mobile networks route 112 to the local emergency service in most countries. Check the local number if you can."
            Basis.UNKNOWN -> "Clairix cannot tell which country the phone is in (no mobile network and no SIM), so the button offers 112: mobile networks route 112 to the local emergency service in most countries."
        }
    }

    /** A country's English name from its code; the code itself when there is none. */
    internal fun countryName(code: String): String =
        runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: code
}
