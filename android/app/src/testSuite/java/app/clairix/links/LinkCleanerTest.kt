package app.clairix.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Clean link": which parameters go, which stay byte for byte, which redirectors are taken off,
 * and that the cleaner and its screen reach for nothing on the network.
 */
class LinkCleanerTest {

    private fun cleaned(link: String): String = LinkCleaner.clean(link).cleaned

    // ── Tracking parameters everywhere ──────────────────────────────────────

    @Test
    fun campaignTagsAndClickIdsGo() {
        assertEquals(
            "https://example.org/article",
            cleaned("https://example.org/article?utm_source=newsletter&utm_medium=email&utm_campaign=autumn&utm_term=x&utm_content=y"),
        )
        for (id in listOf("fbclid", "gclid", "dclid", "msclkid", "mc_eid", "igshid", "yclid", "_hsenc", "_hsmi", "mkt_tok", "gbraid", "wbraid", "ttclid", "twclid")) {
            assertEquals(id, "https://example.org/p", cleaned("https://example.org/p?$id=AbC123"))
        }
    }

    @Test
    fun whatThePageNeedsStaysInItsOrderAndSpelling() {
        assertEquals(
            "https://shop.example/search?q=red%20shoes&page=2&sort=price+asc",
            cleaned("https://shop.example/search?utm_source=a&q=red%20shoes&fbclid=x&page=2&sort=price+asc&gclid=y"),
        )
        // A link with nothing to remove comes back exactly as it was.
        val plain = "https://example.org/a/b?id=7&lang=en#Section_2"
        val result = LinkCleaner.clean(plain)
        assertEquals(plain, result.cleaned)
        assertFalse(result.changed)
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun theFragmentIsKept() {
        assertEquals("https://example.org/doc#part-3", cleaned("https://example.org/doc?utm_source=x#part-3"))
        assertEquals("https://example.org/doc?a=1#utm_source=kept", cleaned("https://example.org/doc?a=1&gclid=2#utm_source=kept"))
    }

    @Test
    fun namesAreComparedWithoutCaseOrEncoding() {
        assertEquals("https://example.org/", cleaned("https://example.org/?UTM_Source=x&FBCLID=y&%75tm_medium=z"))
    }

    @Test
    fun theWordsAroundATrackerAreNotMistakenForOne() {
        // utm_ alone, a name that merely contains a tracker's name, and a value that does are all kept.
        val link = "https://example.org/p?utm_=1&notfbclid=2&x=gclid&mygclid=3"
        assertEquals(link, cleaned(link))
    }

    @Test
    fun emptyParametersAndStrayAmpersandsDoNotBreakIt() {
        assertEquals("https://example.org/p?a=1", cleaned("https://example.org/p?&a=1&&utm_source=x&"))
        assertEquals("https://example.org/p", cleaned("https://example.org/p?utm_source"))
    }

    // ── Per-site parameters ─────────────────────────────────────────────────

    @Test
    fun youTubeAndSpotifyShareIdsGoButTheTimestampStays() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=42", cleaned("https://youtu.be/dQw4w9WgXcQ?si=Ab12Cd34Ef&t=42"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", cleaned("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=xyz&pp=ygUE"))
        assertEquals("https://music.youtube.com/watch?v=abc", cleaned("https://music.youtube.com/watch?v=abc&si=1"))
        assertEquals(
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            cleaned("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=a1b2c3d4e5f6"),
        )
    }

    @Test
    fun siteParametersStayOnOtherSites() {
        // "si", "s" and "t" mean something else on most sites.
        val link = "https://example.org/watch?si=1&s=2&t=3&ref=4&source=5"
        assertEquals(link, cleaned(link))
        // A host that only ends with a site's name is not that site.
        assertEquals("https://notyoutube.com/v?si=1", cleaned("https://notyoutube.com/v?si=1"))
    }

    @Test
    fun socialShareIdsGo() {
        assertEquals("https://www.instagram.com/p/Cx1/", cleaned("https://www.instagram.com/p/Cx1/?igsh=MWQ1ZGUxMzBkMA=="))
        assertEquals("https://x.com/someone/status/123", cleaned("https://x.com/someone/status/123?s=46&t=AbCdEf"))
        assertEquals("https://twitter.com/a/status/1", cleaned("https://twitter.com/a/status/1?ref_src=twsrc%5Etfw"))
        assertEquals("https://www.facebook.com/share/p/1/", cleaned("https://www.facebook.com/share/p/1/?mibextid=WC7FNe"))
        assertEquals("https://www.tiktok.com/@a/video/1", cleaned("https://www.tiktok.com/@a/video/1?_r=1&_t=8kX&is_from_webapp=1&sender_device=pc"))
        assertEquals("https://www.reddit.com/r/a/comments/b/c/", cleaned("https://www.reddit.com/r/a/comments/b/c/?share_id=Zz&utm_content=1&utm_medium=android_app"))
        assertEquals("https://www.linkedin.com/posts/a-b", cleaned("https://www.linkedin.com/posts/a-b?trk=public_post&lipi=urn"))
    }

    @Test
    fun amazonLosesItsRefSegmentAndSearchIds() {
        assertEquals(
            "https://www.amazon.de/dp/B0ABCDEF12?th=1",
            cleaned("https://www.amazon.de/dp/B0ABCDEF12/ref=sr_1_3?crid=2X&qid=1700000000&sr=8-3&th=1"),
        )
        val r = LinkCleaner.clean("https://www.amazon.com/Some-Thing/dp/B01/ref=cm_sw_r_cp")
        assertEquals("https://www.amazon.com/Some-Thing/dp/B01", r.cleaned)
        assertTrue(r.changed)
        // "/ref=" means nothing special elsewhere.
        assertEquals("https://example.org/ref=docs", cleaned("https://example.org/ref=docs"))
    }

    // ── Redirectors ─────────────────────────────────────────────────────────

    @Test
    fun googleRedirectsAreUnwrappedAndWhatTheyCarryIsCleaned() {
        val r = LinkCleaner.clean(
            "https://www.google.com/url?sa=t&rct=j&q=&esrc=s&url=https%3A%2F%2Fnews.example%2Fstory%3Fid%3D9%26utm_source%3Dgoogle&usg=AOvVaw"
        )
        assertEquals("https://news.example/story?id=9", r.cleaned)
        assertEquals(listOf("www.google.com"), r.unwrapped)
        assertEquals(listOf("utm_source"), r.removed)
        assertEquals("https://example.org/a", cleaned("https://google.co.uk/url?q=https://example.org/a&sa=D"))
        assertEquals("https://example.org/b", cleaned("https://www.google.de/url?q=https%3A%2F%2Fexample.org%2Fb"))
    }

    @Test
    fun facebookAndInstagramRedirectsAreUnwrapped() {
        assertEquals(
            "https://example.org/x?id=1",
            cleaned("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Fx%3Fid%3D1%26fbclid%3DIwAR0&h=AT0abc"),
        )
        assertEquals("https://example.org/y", cleaned("https://lm.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Fy"))
        assertEquals("https://example.org/z", cleaned("https://l.instagram.com/?u=https%3A%2F%2Fexample.org%2Fz&e=AT1"))
        assertEquals("https://example.org/v", cleaned("https://www.youtube.com/redirect?event=video_description&redir_token=Q&q=https%3A%2F%2Fexample.org%2Fv"))
    }

    @Test
    fun nestedRedirectsAreUnwrappedInTurn() {
        val inner = "https%3A%2F%2Fl.facebook.com%2Fl.php%3Fu%3Dhttps%253A%252F%252Fexample.org%252Fdeep"
        val r = LinkCleaner.clean("https://www.google.com/url?q=$inner")
        assertEquals("https://example.org/deep", r.cleaned)
        assertEquals(listOf("www.google.com", "l.facebook.com"), r.unwrapped)
    }

    @Test
    fun aRedirectorWithoutAnAddressIsLeftAlone() {
        // Google's own search pages and a /url with nothing usable in it.
        val search = "https://www.google.com/search?q=clairix"
        assertEquals(search, cleaned(search))
        val notALink = "https://www.google.com/url?q=javascript%3Aalert(1)"
        assertEquals(notALink, cleaned(notALink))
        // A host that looks like Google but is not one.
        val lookalike = "https://google.evil.example/url?q=https%3A%2F%2Fexample.org"
        assertEquals(lookalike, cleaned(lookalike))
        assertEquals("https://googleusercontent.example/url?q=x", cleaned("https://googleusercontent.example/url?q=x"))
    }

    @Test
    fun aRedirectorThatLoopsStops() {
        var link = "https://example.org/end"
        repeat(8) { link = "https://www.google.com/url?q=" + java.net.URLEncoder.encode(link, "UTF-8") }
        val r = LinkCleaner.clean(link)
        assertEquals(5, r.unwrapped.size)
        assertTrue(r.cleaned.startsWith("https://www.google.com/url?q="))
    }

    // ── Shapes of links ─────────────────────────────────────────────────────

    @Test
    fun portsUserInfoAndCaseInTheHostAreHandled() {
        assertEquals("https://EXAMPLE.org:8443/p?a=1", cleaned("https://EXAMPLE.org:8443/p?a=1&utm_source=x"))
        assertEquals("HTTPS://WWW.YOUTUBE.COM/watch?v=1", cleaned("HTTPS://WWW.YOUTUBE.COM/watch?v=1&si=2"))
        assertEquals("https://user@example.org/p", cleaned("https://user@example.org/p?gclid=1"))
        assertEquals("http://[2001:db8::1]:8080/p", cleaned("http://[2001:db8::1]:8080/p?fbclid=1"))
    }

    @Test
    fun anythingThatIsNotAWebLinkIsReturnedAsGiven() {
        for (s in listOf("mailto:a@example.org?utm_source=x", "ftp://example.org/?fbclid=1", "not a link", "", "https://", "intent://x#Intent;end")) {
            val r = LinkCleaner.clean(s)
            assertEquals(s, r.cleaned)
            assertFalse(r.changed)
        }
    }

    @Test
    fun percentDecodingToleratesStrayPercentSigns() {
        assertEquals("100% sure", LinkCleaner.decode("100%25+sure").replace("%%", "%"))
        assertEquals("a%zz", LinkCleaner.decode("a%zz"))
        assertEquals("ü", LinkCleaner.decode("%C3%BC"))
        assertEquals("50%", LinkCleaner.decode("50%"))
    }

    // ── Shared text ─────────────────────────────────────────────────────────

    @Test
    fun everyLinkInASharedTextIsCleanedAndTheWordsStay() {
        val text = "Look at this (https://example.org/a?utm_source=x). And https://youtu.be/v?si=q, too!"
        val r = LinkCleaner.cleanText(text)
        assertEquals("Look at this (https://example.org/a). And https://youtu.be/v, too!", r.text)
        assertEquals(2, r.links.size)
        assertEquals("https://example.org/a", r.firstLink)
        assertTrue(r.changed)
    }

    @Test
    fun aClosingBracketThatBelongsToTheLinkIsKept() {
        val link = "https://en.wikipedia.org/wiki/Tracking_(disambiguation)"
        assertEquals(link, LinkCleaner.cleanText(link).text)
        assertEquals("$link.", LinkCleaner.cleanText("$link.").text)
        assertEquals(link, LinkCleaner.cleanText(link).firstLink)
    }

    @Test
    fun aTextWithoutLinksIsUntouched() {
        val r = LinkCleaner.cleanText("nothing to see here")
        assertEquals("nothing to see here", r.text)
        assertNull(r.firstLink)
        assertFalse(r.changed)
    }

    // ── No network ──────────────────────────────────────────────────────────

    /** The cleaner and its screen, as shipped. */
    private val sources: List<File> = listOf("LinkCleaner.kt", "LinkCleanerActivity.kt").map { name ->
        listOf("src/suite/java/app/clairix/links/$name", "app/src/suite/java/app/clairix/links/$name").map(::File).first { it.exists() }
    }

    @Test
    fun nothingHereCanReachTheNetwork() {
        val forbidden = listOf(
            "java.net.URL", "java.net.Socket", "HttpURLConnection", "okhttp3", "java.net.InetAddress", "URLConnection",
            "WebView", "DownloadManager", "Volley", "ktor",
        )
        for (f in sources) {
            val text = f.readText()
            for (word in forbidden) assertFalse("${f.name} mentions $word", text.contains(word))
        }
    }

    @Test
    fun theScreenIsAShareTargetAndATextSelectionAction() {
        val manifest = listOf("src/suite/AndroidManifest.xml", "app/src/suite/AndroidManifest.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        val activity = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == ".links.LinkCleanerActivity" }
        assertEquals("true", activity.getAttributeNS(ns, "exported"))
        val filters = activity.getElementsByTagName("intent-filter")
        val actions = HashMap<String, String>()
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as Element
            val action = (filter.getElementsByTagName("action").item(0) as Element).getAttributeNS(ns, "name")
            val mime = (filter.getElementsByTagName("data").item(0) as Element).getAttributeNS(ns, "mimeType")
            actions[action] = mime
        }
        assertEquals(mapOf("android.intent.action.SEND" to "text/plain", "android.intent.action.PROCESS_TEXT" to "text/plain"), actions)
    }
}
