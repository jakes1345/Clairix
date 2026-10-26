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
    }

    @Test
    fun requestsLeaveThroughTor() {
        client().newCall(Request.Builder().url("https://check.torproject.org/api/ip").build()).execute().use { resp ->
            assertTrue("HTTP ${resp.code}", resp.isSuccessful)
            val body = JSONObject(resp.body!!.string())
            assertTrue("the request did not come out of Tor: $body", body.getBoolean("IsTor"))
        }
    }

    @Test
    fun theListedRelayAnswersThroughTorWithItsListedKey() {
        val listed = RelayDirectory.current(context)!!.relays.first()
        assertEquals(listed.key, RelayDirectory.liveKey(listed.url))
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
}
