package app.clairix.links

import java.util.Locale

/**
 * "Clean link": takes a link someone is about to share or open and removes what is there to
 * follow the person who taps it, without asking the network anything. Two things are done, in
 * this order, until nothing more changes:
 *
 * - A redirector's wrapper is taken off: a link through google.com/url?q=, l.facebook.com/l.php?u=
 *   and the like is replaced by the address it carries. The wrapper exists to record the tap.
 * - Tracking parameters are removed from the query: campaign tags (utm_*), click identifiers that
 *   tie an ad click to a person (fbclid, gclid, msclkid, …), mail-campaign recipient ids (mc_eid,
 *   _hsenc, mkt_tok, …), and on the services that use them, share identifiers that say who shared
 *   the link with whom (si on YouTube and Spotify, igsh on Instagram, …).
 *
 * The rules are Clairix's own list, kept here and tested in LinkCleanerTest; no rule file is
 * fetched or bundled from anywhere else. Everything is string work on the text as given: no URL
 * is resolved, no host is looked up, nothing is fetched. (The JDK's URL class is not used on purpose:
 * its equals and hashCode resolve the host.) Parameters that are kept are kept byte for byte, in their
 * order, as is the fragment.
 */
object LinkCleaner {

    /** The outcome for one link: [cleaned] is what to share; [removed] the parameters taken out, [unwrapped] the redirectors taken off. */
    data class Cleaned(val original: String, val cleaned: String, val removed: List<String>, val unwrapped: List<String>) {
        val changed: Boolean get() = cleaned != original
    }

    /** The outcome for a shared text: [text] with every link in it cleaned, and each link's [Cleaned]. */
    data class CleanedText(val original: String, val text: String, val links: List<Cleaned>) {
        val changed: Boolean get() = text != original
        /** The first link, cleaned, for "Open"; null when the text holds none. */
        val firstLink: String? get() = links.firstOrNull()?.cleaned
    }

    /** How many redirectors may be nested before the cleaner stops unwrapping (a link that wraps itself). */
    private const val MAX_UNWRAP = 5

    /** Amazon's shops: the "/ref=" path segment and the query's ref and search-position ids track. */
    private val AMAZON = setOf(
        "amazon.com", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.it", "amazon.es", "amazon.ca", "amazon.com.au",
        "amazon.in", "amazon.co.jp", "amazon.nl", "amazon.se", "amazon.pl", "amazon.com.mx", "amazon.com.br",
    )

    /** Removed wherever they appear: names compared without case. */
    private val TRACKING_PARAMS = setOf(
        // Ad click identifiers: tie the click to the person who saw the ad.
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "ysclid", "twclid",
        "ttclid", "li_fat_id", "epik", "rdt_cid", "irclickid", "srsltid",
        // Mail and marketing-automation recipient ids: name who opened which mail.
        "mc_eid", "mc_cid", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp", "hsctatracking", "mkt_tok",
        "vero_id", "vero_conv", "oly_anon_id", "oly_enc_id", "rb_clickid", "s_cid", "wickedid", "_kx",
        "ml_subscriber", "ml_subscriber_hash", "sc_cid", "cmpid",
        // Analytics cross-site linkers.
        "_ga", "_gl", "_openstat",
        // Instagram's share id, on any host it was copied to.
        "igshid", "igsh",
    )

    /** Removed wherever they appear: every parameter whose name starts with one of these. */
    private val TRACKING_PREFIXES = listOf("utm_", "pk_", "mtm_", "pd_rd_", "pf_rd_")

    /**
     * Removed only on the services that use them for tracking, where elsewhere the same name may
     * carry the page itself: a site, and its subdomains, to the parameters stripped there.
     */
    private val SITE_PARAMS: List<Pair<Set<String>, Set<String>>> = listOf(
        // Share identifiers: who shared the video or track with whom.
        setOf("youtube.com", "youtu.be", "music.youtube.com") to setOf("si", "pp", "feature"),
        setOf("spotify.com", "spotify.link") to setOf("si", "nd", "context", "sp_cid", "dl_branch"),
        setOf("instagram.com") to setOf("igshid", "igsh", "ig_rid"),
        setOf("facebook.com", "fb.watch") to setOf("mibextid", "rdid", "share_url", "__tn__", "__cft__", "sfnsn"),
        setOf("twitter.com", "x.com") to setOf("s", "t", "ref_src", "ref_url"),
        setOf("tiktok.com") to setOf("_r", "_t", "is_from_webapp", "sender_device", "share_app_id", "share_link_id", "u_code", "tt_from"),
        setOf("reddit.com") to setOf("share_id", "rdt", "ref_source", "ref_campaign"),
        setOf("linkedin.com") to setOf("trk", "trkemail", "trackingid", "lipi", "refid", "midtoken", "midsig", "eid"),
        setOf("threads.net") to setOf("xmt", "slof"),
        AMAZON to setOf("ref", "ref_", "_encoding", "psc", "content-id", "crid", "sprefix", "qid", "sr", "dib", "dib_tag"),
        setOf("aliexpress.com") to setOf("spm", "scm", "pvid", "algo_pvid", "algo_exp_id", "aff_platform", "sk", "aff_trace_key"),
        setOf("ebay.com", "ebay.co.uk", "ebay.de") to setOf("_trkparms", "_trksid", "mkcid", "mkevt", "mkrid", "campid", "toolid", "customid"),
        setOf("medium.com") to setOf("source", "sk"),
        setOf("substack.com") to setOf("r", "triedredirect", "publication_id", "post_id", "isfreemail"),
    )

    /** A redirector: [path] on any of [sites], which carries the real address in the first of [params] that is present. */
    private class Redirector(val sites: (String) -> Boolean, val path: String, val params: List<String>)

    private val REDIRECTORS = listOf(
        // google.com/url?q=… and every country's Google (google.de, google.co.uk, …).
        Redirector({ isGoogle(it) }, "/url", listOf("q", "url")),
        Redirector({ it == "l.facebook.com" || it == "lm.facebook.com" || it == "l.messenger.com" }, "/l.php", listOf("u")),
        Redirector({ it == "l.instagram.com" }, "/", listOf("u")),
        Redirector({ it == "l.threads.net" }, "/", listOf("u")),
        Redirector({ siteOf(it, setOf("youtube.com")) }, "/redirect", listOf("q")),
        Redirector({ it == "href.li" }, "/", emptyList()),
    )

    private fun isGoogle(host: String): Boolean {
        val h = host.removePrefix("www.")
        if (!h.startsWith("google.")) return false
        val tld = h.removePrefix("google.")
        // google.com, google.de, google.co.uk, google.com.au: one or two short labels.
        return tld.split('.').let { labels -> labels.size in 1..2 && labels.all { it.length in 2..3 && it.all(Char::isLetter) } }
    }

    /** Whether [host] is one of [sites] or a subdomain of one. */
    private fun siteOf(host: String, sites: Set<String>): Boolean = sites.any { host == it || host.endsWith(".$it") }

    // ── Shared text ─────────────────────────────────────────────────────────

    /** An http(s) link in running text, up to the first space, quote or angle bracket. */
    private val LINK = Regex("""(?i)\bhttps?://[^\s<>"'`]+""")

    /** Punctuation that ends a sentence rather than the link it follows. */
    private const val TRAILING = ".,;:!?'\"”’»"

    /**
     * Cleans every http(s) link in [text], leaving the words around them as they are. A link is
     * taken to end at whitespace; full stops, commas and a closing bracket with no opening one
     * inside the link are left to the sentence.
     */
    fun cleanText(text: String): CleanedText {
        val links = ArrayList<Cleaned>()
        val out = StringBuilder()
        var last = 0
        for (m in LINK.findAll(text)) {
            val raw = trimTrailing(m.value)
            val start = m.range.first
            out.append(text, last, start)
            val c = clean(raw)
            links += c
            out.append(c.cleaned)
            last = start + raw.length
        }
        out.append(text, last, text.length)
        return CleanedText(text, out.toString(), links)
    }

    private fun trimTrailing(link: String): String {
        var end = link.length
        while (end > 0) {
            val ch = link[end - 1]
            val unbalanced = (ch == ')' && link.substring(0, end).count { it == '(' } < link.substring(0, end).count { it == ')' }) ||
                (ch == ']' && link.substring(0, end).count { it == '[' } < link.substring(0, end).count { it == ']' })
            if (ch in TRAILING || unbalanced) end-- else break
        }
        return link.substring(0, end)
    }

    // ── One link ────────────────────────────────────────────────────────────

    /** Cleans one http(s) link; anything that is not one is returned unchanged. */
    fun clean(link: String): Cleaned {
        val removed = ArrayList<String>()
        val unwrapped = ArrayList<String>()
        var current = link.trim()
        var parts = Parts.parse(current) ?: return Cleaned(link, link, emptyList(), emptyList())
        while (unwrapped.size < MAX_UNWRAP) {
            val target = unwrap(parts) ?: break
            unwrapped += parts.host
            current = target
            parts = Parts.parse(target) ?: break
        }
        val kept = ArrayList<String>()
        for (param in parts.params) {
            val name = paramName(param)
            if (tracking(name, parts.host)) removed += name else kept += param
        }
        val path = sitePath(parts)
        val cleaned = if (removed.isEmpty() && path == parts.path) current else parts.rebuild(path, kept)
        if (path != parts.path) removed += "ref (in the path)"
        return Cleaned(link, cleaned, removed, unwrapped)
    }

    /**
     * [p]'s path with what tracks taken out of it: on Amazon, the "/ref=…" segment that records
     * which list or search the link was copied from. Any other path is returned as it is.
     */
    private fun sitePath(p: Parts): String {
        if (!siteOf(p.host, AMAZON)) return p.path
        val at = p.path.indexOf("/ref=")
        if (at < 0) return p.path
        val end = p.path.indexOf('/', at + 1).let { if (it < 0) p.path.length else it }
        return p.path.substring(0, at) + p.path.substring(end)
    }

    /** The address a redirector link carries, when [p] is one and it carries an http(s) link; null otherwise. */
    private fun unwrap(p: Parts): String? {
        for (r in REDIRECTORS) {
            if (!r.sites(p.host)) continue
            if (r.params.isEmpty()) {
                // href.li/?https://… : the whole query is the address.
                if (p.path != r.path && p.path.isNotEmpty()) continue
                val q = p.query ?: continue
                val target = decode(q)
                if (Parts.parse(target) != null) return target
                continue
            }
            if (p.path != r.path) continue
            for (name in r.params) {
                val value = p.params.firstOrNull { paramName(it) == name }?.substringAfter('=', "") ?: continue
                val target = decode(value)
                if (Parts.parse(target) != null) return target
            }
        }
        return null
    }

    /** Whether the parameter [name] (lower case) tracks, on [host]. */
    internal fun tracking(name: String, host: String): Boolean {
        if (name in TRACKING_PARAMS) return true
        if (TRACKING_PREFIXES.any { name.startsWith(it) && name.length > it.length }) return true
        return SITE_PARAMS.any { (sites, params) -> name in params && siteOf(host, sites) }
    }

    /** A parameter's name, percent-decoded and in lower case, for comparison only. */
    private fun paramName(param: String): String = decode(param.substringBefore('=')).lowercase(Locale.ROOT)

    /**
     * Percent-decoding as a form does it ('+' is a space), tolerant of a stray '%': what cannot be
     * decoded is kept as written. Bytes are read as UTF-8.
     */
    internal fun decode(s: String): String {
        if ('%' !in s && '+' !in s) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { bytes.write(' '.code); i++ }
                c == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0 -> {
                    bytes.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
                    i += 3
                }
                else -> {
                    val chunk = c.toString().toByteArray(Charsets.UTF_8)
                    bytes.write(chunk, 0, chunk.size)
                    i++
                }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * An http(s) link taken apart as written: scheme and authority ([origin]), the path, the
     * query's parameters as written, and the fragment with its '#'.
     */
    private class Parts(val origin: String, val host: String, val path: String, val query: String?, val fragment: String) {
        val params: List<String> get() = query?.split('&')?.filter { it.isNotEmpty() } ?: emptyList()

        /** The link again with [newPath] and only the [kept] parameters, in their order; no '?' when none is left. */
        fun rebuild(newPath: String, kept: List<String>): String =
            origin + newPath + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment

        companion object {
            fun parse(link: String): Parts? {
                val s = link.trim()
                val schemeEnd = s.indexOf("://")
                if (schemeEnd <= 0) return null
                val scheme = s.substring(0, schemeEnd).lowercase(Locale.ROOT)
                if (scheme != "http" && scheme != "https") return null
                if (s.any { it.isWhitespace() }) return null
                val hashAt = s.indexOf('#').let { if (it < 0) s.length else it }
                val fragment = s.substring(hashAt)
                val beforeHash = s.substring(0, hashAt)
                val queryAt = beforeHash.indexOf('?')
                val head = if (queryAt < 0) beforeHash else beforeHash.substring(0, queryAt)
                val query = if (queryAt < 0) null else beforeHash.substring(queryAt + 1)
                val rest = head.substring(schemeEnd + 3)
                val slash = rest.indexOf('/')
                val authority = if (slash < 0) rest else rest.substring(0, slash)
                val path = if (slash < 0) "" else rest.substring(slash)
                val host = hostOf(authority) ?: return null
                return Parts(s.substring(0, schemeEnd + 3) + authority, host, path, query, fragment)
            }

            /** The host of an authority (user info and port dropped), lower case; null when there is none. */
            private fun hostOf(authority: String): String? {
                val hostPort = authority.substringAfterLast('@')
                val host = if (hostPort.startsWith("[")) {
                    hostPort.substringBefore(']') + "]"
                } else {
                    hostPort.substringBefore(':')
                }
                return host.lowercase(Locale.ROOT).trimEnd('.').takeIf { it.isNotEmpty() }
            }
        }
    }
}
