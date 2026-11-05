package app.clairix.analysis

/**
 * A small, strict reader for DER, the byte encoding of ASN.1 that X.509 certificates and Android's
 * key attestation record ([KeyAttestation]) are written in. Clairix reads one structure with it and
 * takes no library for that: fewer parts to trust, and nothing new to ship.
 *
 * Strict means it refuses, rather than guesses at, anything DER does not allow: an indefinite
 * length, a length written in more bytes than it needs or running past the end of its parent, a
 * tag number in the long form that fits the short one, an INTEGER with a needless leading byte,
 * and trailing bytes after the one element asked for. Every refusal is a [DerException]. The one
 * leniency is a BOOLEAN TRUE written as 01 instead of FF ([Element.bool]): older Keymaster versions
 * wrote it that way, and it cannot be read as anything else.
 *
 * Nothing is copied or recursed into until asked for, and nesting is capped at [MAX_DEPTH].
 */
internal object Der {

    const val CLASS_UNIVERSAL = 0
    const val CLASS_CONTEXT = 2

    const val TAG_BOOLEAN = 1
    const val TAG_INTEGER = 2
    const val TAG_OCTET_STRING = 4
    const val TAG_ENUMERATED = 10
    const val TAG_SEQUENCE = 16
    const val TAG_SET = 17

    /** How deep [Element.children] may go below the element [parse] returned. */
    const val MAX_DEPTH = 32

    /** A DER element: its tag, and where its content lies in [buf]. */
    class Element internal constructor(
        val tagClass: Int,
        val constructed: Boolean,
        val tag: Int,
        private val buf: ByteArray,
        private val start: Int,
        val length: Int,
        private val depth: Int,
    ) {
        /** Whether this is the universal, primitive or constructed, element with [tag]. */
        fun isUniversal(tag: Int): Boolean = tagClass == CLASS_UNIVERSAL && this.tag == tag

        /** A copy of the content bytes. */
        fun content(): ByteArray = buf.copyOfRange(start, start + length)

        /** The elements inside a constructed element, in order. */
        fun children(): List<Element> {
            if (!constructed) fail("tag $tag is not constructed")
            if (depth >= MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
            return readAll(buf, start, start + length, depth + 1)
        }

        /** The one element inside an explicitly tagged element ([n] EXPLICIT). */
        fun explicit(): Element = children().singleOrNull() ?: fail("explicit tag $tag does not hold exactly one element")

        /** The value of an INTEGER or ENUMERATED that fits a Long, read strictly. */
        fun long(): Long {
            val numeric = isUniversal(TAG_INTEGER) || isUniversal(TAG_ENUMERATED)
            if (!numeric || constructed) fail("not an INTEGER or ENUMERATED (tag $tag)")
            if (length == 0) fail("empty INTEGER")
            if (length > 8) fail("INTEGER longer than 8 bytes")
            if (length > 1) {
                // The first nine bits all equal: the first byte only repeats the sign of the second.
                val ninth = (buf[start].toInt() shl 1) or ((buf[start + 1].toInt() ushr 7) and 1)
                if (ninth == 0 || ninth == -1) fail("INTEGER has a needless leading byte")
            }
            var v = buf[start].toLong() // sign-extended
            for (i in 1 until length) v = (v shl 8) or (buf[start + i].toLong() and 0xFF)
            return v
        }

        /** The value of a BOOLEAN: 00 is false, FF (or 01, see [Der]) is true, anything else is refused. */
        fun bool(): Boolean {
            if (!isUniversal(TAG_BOOLEAN) || constructed || length != 1) fail("not a one-byte BOOLEAN")
            return when (buf[start].toInt() and 0xFF) {
                0x00 -> false
                0xFF, 0x01 -> true
                else -> fail("BOOLEAN is neither 00 nor FF")
            }
        }

        /** The bytes of a primitive OCTET STRING. */
        fun octets(): ByteArray {
            if (!isUniversal(TAG_OCTET_STRING) || constructed) fail("not a primitive OCTET STRING")
            return content()
        }
    }

    /** Every refusal goes through here, so each check reads as one line. */
    private fun fail(message: String): Nothing = throw DerException(message)

    /** The one element that is the whole of [bytes]; trailing bytes are refused. */
    fun parse(bytes: ByteArray): Element {
        val all = readAll(bytes, 0, bytes.size, 0)
        return all.singleOrNull() ?: fail(if (all.isEmpty()) "no element" else "${all.size - 1} trailing element(s)")
    }

    /** Every element from [from] to exactly [to]. */
    private fun readAll(buf: ByteArray, from: Int, to: Int, depth: Int): List<Element> {
        val out = ArrayList<Element>()
        var at = from
        while (at < to) {
            val (element, next) = readOne(buf, at, to, depth)
            out += element
            at = next
        }
        return out
    }

    /** The element starting at [at], which must end by [limit], and where the next one starts. */
    private fun readOne(buf: ByteArray, at: Int, limit: Int, depth: Int): Pair<Element, Int> {
        var i = at
        fun next(): Int {
            if (i >= limit) fail("truncated at byte $i")
            return buf[i++].toInt() and 0xFF
        }
        val first = next()
        val tagClass = first ushr 6
        val constructed = first and 0x20 != 0
        var tag = first and 0x1F
        if (tag == 0x1F) {
            tag = 0
            var b = next()
            if (b == 0x80) fail("tag number with a needless leading byte")
            var n = 0
            while (true) {
                if (++n > 4) fail("tag number too long")
                tag = (tag shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
                b = next()
            }
            if (tag < 0x1F) fail("tag number $tag written in the long form")
        }
        val lead = next()
        val length = when {
            lead < 0x80 -> lead
            lead == 0x80 -> fail("indefinite length")
            else -> {
                val n = lead and 0x7F
                if (n > 4) fail("length of $n bytes")
                var len = 0L
                for (k in 0 until n) {
                    val b = next()
                    if (k == 0 && b == 0) fail("length with a needless leading byte")
                    len = (len shl 8) or b.toLong()
                }
                if (len < 0x80) fail("length $len written in the long form")
                if (len > Int.MAX_VALUE) fail("length $len")
                len.toInt()
            }
        }
        if (length > limit - i) fail("length $length runs past the end")
        return Element(tagClass, constructed, tag, buf, i, length, depth) to (i + length)
    }
}

/** DER that [Der] refuses to read. */
internal class DerException(message: String) : Exception(message)
