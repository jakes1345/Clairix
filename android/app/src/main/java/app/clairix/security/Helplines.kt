package app.clairix.security

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The helplines on the Get help page, bundled as assets/helplines.json and
 * keyed by ISO 3166 country code. Every number in the file was checked against
 * the service's own website or an official government page, and each line and
 * each emergency number carries that page's address and the date it was
 * checked: an entry missing either is dropped here, so nothing unchecked can
 * reach the page. The page shows the
 * country of the SIM card, then the phone's region, and lets the owner pick
 * any other. Nothing here is Android but [load] and [simCountry], so the rules
 * are tested on the JVM.
 */
object Helplines {

    const val ASSET = "helplines.json"

    /** How the country shown was chosen. */
    enum class Match { SIM, REGION, NONE }

    /**
     * The country to show, and how it was found. With [Match.NONE], [unmatched]
     * is the country the phone reported that the list has nothing for (null when
     * it reported none), so the page can name it.
     */
    data class Choice(val country: Country?, val match: Match, val unmatched: String? = null)

    /**
     * The list, and [directory]: a directory of helplines beyond it, for a phone
     * in a country the list does not have, checked like every line.
     */
    data class Book(val countries: Map<String, Country>, val directory: Directory? = null) {
        /** Every country, by name, for the page's picker. */
        val byName: List<Country> get() = countries.values.sortedBy { it.name }
    }

    /** An outside directory of helplines: [url] opens in the browser. */
    data class Directory(val label: String, val url: String, val note: String?, val source: String, val verified: LocalDate)

    data class Country(val code: String, val name: String, val emergency: Emergency?, val lines: List<Line>)

    /** The country's emergency number, with what to do when speaking is not possible. */
    data class Emergency(
        val number: String,
        val note: String?,
        val text: TextOption?,
        val source: String,
        val verified: LocalDate
    )

    data class Line(
        val name: String,
        /** Where or whom it serves, when not the whole country: "Ontario only", "For men". */
        val area: String?,
        val phone: String,
        val hours: String,
        val note: String?,
        /** Other numbers for the same service: a TTY line, a mobile shortcut. */
        val also: List<Extra>,
        /** For when calling is not safe: a number that takes texts. */
        val text: TextOption?,
        /** For when calling is not safe: a chat page or a messaging app. */
        val chat: List<ChatOption>,
        val source: String,
        val verified: LocalDate
    )

    data class Extra(val label: String, val phone: String)

    /** A text message: [to] as the messaging app is handed it, [body] typed in for the owner when the service asks for a keyword. */
    data class TextOption(val label: String, val to: String, val body: String?, val hours: String?)

    data class ChatOption(val label: String, val url: String, val hours: String?)

    private val DATE_SHOWN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    /** Reads the bundled list. Disk I/O; call off the main thread. */
    fun load(context: Context): Book =
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })

    /** The SIM card's country as Android reports it (no permission needed), or null with no SIM. */
    fun simCountry(context: Context): String? = runCatching {
        context.getSystemService(TelephonyManager::class.java)?.simCountryIso
    }.getOrNull()

    /**
     * Parses the list. A line without a name, a number, its hours, an https
     * source or a verified date is dropped, and so is a text or chat option
     * that is malformed; a country left with no line and no emergency number
     * is dropped with it. The directory needs a label, an https link, an https
     * source and a verified date, or it is dropped.
     */
    fun parse(json: String): Book {
        val root = JSONObject(json)
        val directory = root.optJSONObject("directory")?.let(::directory)
        val countries = root.optJSONObject("countries") ?: return Book(emptyMap(), directory)
        val out = LinkedHashMap<String, Country>()
        for (key in countries.keys()) {
            val code = normalize(key) ?: continue
            val c = countries.optJSONObject(key) ?: continue
            val name = c.str("name") ?: continue
            val emergency = c.optJSONObject("emergency")?.let(::emergency)
            val lines = c.optJSONArray("lines").objects().mapNotNull(::line)
            if (lines.isEmpty() && emergency == null) continue
            out[code] = Country(code, name, emergency, lines)
        }
        return Book(out, directory)
    }

    /** The outside directory, offered only when no country from the list is shown. */
    fun directoryFor(book: Book, shown: Country?): Directory? = if (shown == null) book.directory else null

    /**
     * A country code as the list keys it: two letters, upper case. The United
     * Kingdom's ISO code is GB; "UK", which some settings use, is read as GB.
     * Anything else (blank, three letters, digits) is null.
     */
    fun normalize(code: String?): String? {
        val c = code?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return null
        return if (c == "UK") "GB" else c
    }

    /** The SIM card's country when the list has it, else the phone's region when it has that, else none. */
    fun choose(book: Book, simCountry: String?, regionCountry: String?): Choice {
        val sim = normalize(simCountry)
        val region = normalize(regionCountry)
        sim?.let { book.countries[it] }?.let { return Choice(it, Match.SIM) }
        region?.let { book.countries[it] }?.let { return Choice(it, Match.REGION) }
        return Choice(null, Match.NONE, unmatched = sim ?: region)
    }

    /** The oldest check behind what a country shows: the date the page gives as "last verified". */
    fun lastVerified(country: Country): LocalDate? =
        (country.lines.map { it.verified } + listOfNotNull(country.emergency?.verified)).minOrNull()

    fun formatDate(date: LocalDate): String = DATE_SHOWN.format(date)

    /**
     * A printed number as the dialler is handed it: digits, and a leading +, *
     * or # kept ("#7233" is a mobile shortcut), with spaces and dashes gone.
     */
    fun dialString(phone: String): String = phone.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun emergency(o: JSONObject): Emergency? {
        val number = o.str("number")?.takeIf { dialString(it).isNotEmpty() } ?: return null
        val source = o.str("source")?.takeIf(::isHttps) ?: return null
        val verified = o.date("verified") ?: return null
        return Emergency(number, o.str("note"), o.optJSONObject("text")?.let(::text), source, verified)
    }

    private fun directory(o: JSONObject): Directory? {
        val label = o.str("label") ?: return null
        val url = o.str("url")?.takeIf(::isHttps) ?: return null
        val source = o.str("source")?.takeIf(::isHttps) ?: return null
        val verified = o.date("verified") ?: return null
        return Directory(label, url, o.str("note"), source, verified)
    }

    private fun line(o: JSONObject): Line? {
        val name = o.str("name") ?: return null
        val phone = o.str("phone")?.takeIf { dialString(it).isNotEmpty() } ?: return null
        val hours = o.str("hours") ?: return null
        val source = o.str("source")?.takeIf(::isHttps) ?: return null
        val verified = o.date("verified") ?: return null
        return Line(
            name = name,
            area = o.str("area"),
            phone = phone,
            hours = hours,
            note = o.str("note"),
            also = o.optJSONArray("also").objects().mapNotNull { x ->
                val label = x.str("label")
                val number = x.str("phone")?.takeIf { dialString(it).isNotEmpty() }
                if (label == null || number == null) null else Extra(label, number)
            },
            text = o.optJSONObject("text")?.let(::text),
            chat = o.optJSONArray("chat").objects().mapNotNull { x ->
                val label = x.str("label")
                val url = x.str("url")?.takeIf(::isHttps)
                if (label == null || url == null) null else ChatOption(label, url, x.str("hours"))
            },
            source = source,
            verified = verified
        )
    }

    private fun text(o: JSONObject): TextOption? {
        val label = o.str("label") ?: return null
        // What the messaging app is handed: digits, with a leading + for an international number.
        val to = o.str("to")?.takeIf { it.matches(Regex("""\+?\d{3,15}""")) } ?: return null
        return TextOption(label, to, o.str("body"), o.str("hours"))
    }

    private fun isHttps(url: String): Boolean = url.startsWith("https://") && url.length > "https://".length

    /** The string under [key], trimmed; null when it is missing, JSON null or blank. */
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.date(key: String): LocalDate? {
        val s = str(key) ?: return null
        return try {
            LocalDate.parse(s)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
