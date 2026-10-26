package app.clairix.comms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * "Connect through Tor" on a real phone and the real Tor network: Arti in
 * the native library, its SOCKS proxy, the JDK's SOCKS client logging in
 * through the Authenticator, and OkHttp choosing the proxy per connection.
 * Needs the internet, so it runs on its own:
 *
 *   am instrument -e class app.clairix.comms.TorDeviceTest -e tor true ...
 *
 * With `-e onionRelay <its loopback address> -e onion http://….onion -e secret
 * <ENROLL_SECRET>` it also registers at a relay that publishes that onion
 * address (CI runs a real onion service for the self-hosted relay) and
 * requires the app to keep working through it.
 */
@RunWith(AndroidJUnit4::class)
class TorDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(TorTransport.proxySelector)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        assumeTrue("pass tor=true to run the tests that use the Tor network", InstrumentationRegistry.getArguments().getString("tor") == "true")
        TorTransport.init(context)
        TorTransport.setEnabled(true)
        runBlocking { withTimeout(300_000) { TorTransport.state.first { it == TorTransport.State.Ready } } }
    }

    @After
    fun tearDown() {
        TorTransport.setEnabled(false)
        if (registered) runCatching { runBlocking { CommsRepository.unpair() } }
    }

    private var registered = false

    @Test
    fun requestsLeaveThroughTor() {
        // Some exit relays close the connection during the TLS handshake ("connection
        // closed"); a Tor client goes again through another circuit, and so does this.
        val failures = mutableListOf<String>()
        repeat(CHECK_TRIES) {
            val body = try {
                client().newCall(Request.Builder().url("https://check.torproject.org/api/ip").build()).execute().use { resp ->
                    assertTrue("HTTP ${resp.code}", resp.isSuccessful)
                    JSONObject(resp.body!!.string())
                }
            } catch (e: IOException) {
                failures += "${e.javaClass.simpleName}: ${e.message}"
                return@repeat
            }
            assertTrue("the request did not come out of Tor: $body", body.getBoolean("IsTor"))
            return
        }
        throw AssertionError("check.torproject.org was not reached through Tor in $CHECK_TRIES tries: $failures")
    }

    @Test
    fun theListedRelayAnswersThroughTorWithItsListedKey() {
        val listed = RelayDirectory.current(context)!!.relays.first()
        assertEquals(listed.key, RelayDirectory.liveKey(listed.url))
    }

    @Test
    fun theRelayIsReachedAtItsOnionAddress() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val relayUrl = args.getString("onionRelay")
        val onion = args.getString("onion")
        val secret = args.getString("secret")
        assumeTrue("pass onionRelay, onion and secret to test a relay's onion address", relayUrl != null && onion != null && secret != null)
        val repo = CommsRepository
        // Registered at the relay's own address, with Tor off...
        TorTransport.setEnabled(false)
        repo.init(context)
        if (repo.isRegistered()) repo.unpair()
        repo.register(relayUrl!!, secret!!, "Onion", listed = false).getOrThrow()
        registered = true
        // ...which tells the phone its onion address.
        val learnedBy = System.currentTimeMillis() + 60_000
        while (repo.relayOnion() != onion) {
            assertTrue("the relay's onion address was not learned: ${repo.relayOnion()}", System.currentTimeMillis() < learnedBy)
            kotlinx.coroutines.delay(500)
        }

        TorTransport.setEnabled(true)
        withTimeout(300_000) { TorTransport.state.first { it == TorTransport.State.Ready } }
        // Arti takes nothing to 127.0.0.1 out of the Tor network, so from here
        // the relay's registered address cannot answer: only its onion can.
        val loopback = try {
            client().newCall(Request.Builder().url("$relayUrl/").build()).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
        assertTrue("the relay's loopback address answered through Tor", !loopback)

        // A new onion service takes a minute or two to be found on the Tor network.
        val deadline = System.currentTimeMillis() + 300_000
        var invite = repo.createInvite()
        while (invite.isFailure && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(5_000)
            invite = repo.createInvite()
        }
        assertTrue("signed requests did not reach the relay at its onion address: ${invite.exceptionOrNull()}", invite.isSuccess)
        repo.sync().getOrThrow()
    }

    @Test
    fun withTorOnAndItsProxyGoneNothingGoesOutDirectly() {
        TorTransport.killProxyForTest()
        val failed = try {
            client().newCall(Request.Builder().url("https://check.torproject.org/api/ip").build()).execute().use { false }
        } catch (e: IOException) {
            true
        }
        assertTrue("with Tor on but not running, a request went out anyway", failed)
    }

    private companion object {
        const val CHECK_TRIES = 3
    }
}
