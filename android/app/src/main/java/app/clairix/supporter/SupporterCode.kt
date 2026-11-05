package app.clairix.supporter

import java.security.MessageDigest

/**
 * A Supporter code as people type it (docs/SUPPORTER.md): 22 random symbols
 * of Crockford's base32 and 2 check symbols, in six groups of four, such as
 * `7KQ2-M9XD-4TPW-HC3R-0VNB-6YMY`. The relay mints them and keeps only a hash
 * (comms-worker/src/supporter.ts, which has the same rules and the same test
 * vectors). The check lets the phone say a code was mistyped before anything
 * is sent.
 */
object SupporterCode {

    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val DATA_LEN = 22
    const val CHECK_LEN = 2
    const val LENGTH = DATA_LEN + CHECK_LEN
    private const val GROUP = 4
    private const val DOMAIN = "clairix-supporter-code-v1|"
    private const val SYMBOL_MASK = 31
    /** Far longer than any code with spaces and dashes; longer input is not one. */
    private const val MAX_INPUT = 64

    /** The two check symbols of [data], 22 symbols. */
    fun check(data: String): String {
        require(data.length == DATA_LEN) { "a code has $DATA_LEN data symbols" }
        val d = MessageDigest.getInstance("SHA-256").digest("${DOMAIN}check|$data".toByteArray(Charsets.UTF_8))
        return "${ALPHABET[d[0].toInt() and SYMBOL_MASK]}${ALPHABET[d[1].toInt() and SYMBOL_MASK]}"
    }

    /**
     * [raw] as its 24 symbols, or null when it is not a code: case, spaces
     * and dashes do not matter, O reads as 0 and I or L as 1, and the check
     * symbols must match.
     */
    fun normalise(raw: String): String? {
        if (raw.length > MAX_INPUT) return null
        val s = raw.uppercase()
            .filterNot { it.isWhitespace() || it == '-' }
            .replace('O', '0').replace('I', '1').replace('L', '1')
        if (s.length != LENGTH || s.any { it !in ALPHABET }) return null
        return s.takeIf { check(it.substring(0, DATA_LEN)) == it.substring(DATA_LEN) }
    }

    /** [symbols] in groups of four, as printed. */
    fun group(symbols: String): String = symbols.chunked(GROUP).joinToString("-")

    /** What the code field shows while someone types: their symbols, upper case, grouped, at most one code long. */
    fun formatTyping(raw: String): String =
        group(raw.uppercase().filter { it.isLetterOrDigit() }.take(LENGTH))
}
