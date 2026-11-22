package app.clairix.detect

import java.util.Locale

/**
 * Radio fingerprints of attack tools people carry: a Flipper Zero heard over
 * Bluetooth, and the stream of fake pairing requests that it and its relatives send
 * to cover every phone in range in pop-ups.
 *
 * Every identifier here is read out of the tool's own published source, not out of
 * somebody's blog post about it:
 *
 *  - Flipper Zero: the official firmware (flipperdevices/flipperzero-firmware,
 *    GPL-3.0). `serial_profile.c` advertises the 16-bit service 0x3080 with the case
 *    colour added (black 1, white 2, transparent 3); `furi_hal_version.c` names the
 *    device "Flipper " and its given name, and builds its address from the radio chip:
 *    80:E1:26 and three bytes of the chip's serial. `hid_profile.c` is the keyboard
 *    and remote mode: the standard keyboard service under the name "Control " and the
 *    Flipper's name. Its keystroke-script application takes the same profile under
 *    "BadUSB " and the Flipper's name (`bad_usb/helpers/bad_usb_hid.c`). Both change
 *    the low bytes of the address and leave the chip's prefix.
 *  - Flipper Devices' own address block, 0C:FA:22, as registered with the IEEE.
 *  - The fake pairing requests: the "BLE Spam" application shipped with the Momentum
 *    and Xtreme firmwares (`protocols/continuity.c`, `fastpair.c`, `swiftpair.c`,
 *    `easysetup.c`), which builds an Apple, Google, Microsoft or Samsung pairing
 *    frame and, for most of its attacks, gives every single packet a new random
 *    address (`ble_spam.c`, `random_mac`), 2 to 50 packets a second.
 *
 * Honesty about reach. A Flipper is heard only while its Bluetooth is on, and custom
 * firmware lets its owner rename it and change its address, so not hearing one proves
 * nothing. Its other radios (the sub-gigahertz one that replays garage and car remotes,
 * NFC, infrared) are silent to a phone. A pairing frame on its own is what a pair of
 * headphones sends all day; only the pattern below is an attack, and a flood cannot
 * read anything on a phone: it can only cover the screen in requests.
 *
 * The matching functions take plain values so they can be tested without Android.
 */
object AttackTools {

    enum class Kind(val label: String, val headline: String) {
        /** A Flipper Zero advertising its own serial service: its ordinary state with Bluetooth on. */
        FLIPPER_ZERO("Flipper Zero", "A Flipper Zero is nearby"),
        /** A Flipper Zero in its Bluetooth keyboard and remote mode, which a phone or computer can be paired with. */
        FLIPPER_KEYBOARD(
            "Flipper Zero acting as a Bluetooth keyboard",
            "A Flipper Zero is offering itself as a keyboard nearby"
        ),
        /** Many pairing requests, each from an address never heard again, all from one place. */
        PAIRING_FLOOD("Flood of fake pairing requests", "Fake Bluetooth pairing requests are being sent here"),
    }

    /** How sure the match is. POSSIBLE is shown nowhere; it exists so a test can see a near miss. */
    enum class Confidence { POSSIBLE, LIKELY, CERTAIN }

    data class Hit(val kind: Kind, val confidence: Confidence, val evidence: List<String>) {
        val label: String get() = kind.label
    }

    /** The serial service a Flipper advertises: 0x3080 with the case colour in the last digit. */
    private val FLIPPER_SERVICES = mapOf(
        0x3080 to "colour not set", 0x3081 to "black", 0x3082 to "white", 0x3083 to "transparent"
    )

    /** The Bluetooth SIG's Human Interface Device service: what any Bluetooth keyboard advertises. */
    private const val HID_SERVICE = 0x1812

    /** How the firmware builds the address of every Flipper Zero: the STM32WB55 radio's prefix. */
    const val FLIPPER_RADIO_PREFIX = "80:E1:26"

    /** Flipper Devices Inc's IEEE MA-L block. */
    const val FLIPPER_DEVICES_OUI = "0C:FA:22"

    private const val FLIPPER_NAME_PREFIX = "Flipper "
    /** Remote mode, then the keystroke-script application: the two names the keyboard profile is given. */
    private val FLIPPER_HID_NAME_PREFIXES = listOf("Control ", "BadUSB ")

    /**
     * Judges one Bluetooth advertisement. [name] is the advertised name as sent,
     * [services] every 16-bit service UUID in it, [address] the sender's address.
     */
    fun matchBle(name: String?, services: Collection<Int>, address: String?): Hit? {
        val n = name?.trim().orEmpty()
        val mac = address?.uppercase(Locale.US).orEmpty()
        val evidence = ArrayList<String>(3)

        val service = services.firstOrNull { it in FLIPPER_SERVICES }
        if (service != null) {
            evidence += "advertises the Flipper's own service (0x%04X, case colour: %s)".format(Locale.US, service, FLIPPER_SERVICES[service])
        }
        // "Flipper " and a name, as the firmware writes it. A bare "Flipper" is somebody's speaker.
        val named = n.startsWith(FLIPPER_NAME_PREFIX) && n.length > FLIPPER_NAME_PREFIX.length
        if (named) evidence += "named \"$n\", the way the firmware names itself"
        val radio = mac.startsWith(FLIPPER_RADIO_PREFIX)
        val ownBlock = mac.startsWith(FLIPPER_DEVICES_OUI)
        if (radio) evidence += "address begins $FLIPPER_RADIO_PREFIX, as the Flipper's radio chip's does"
        if (ownBlock) evidence += "address is in Flipper Devices' registered block ($FLIPPER_DEVICES_OUI)"
        val addressed = radio || ownBlock

        if (service != null) {
            return Hit(Kind.FLIPPER_ZERO, if (named || addressed) Confidence.CERTAIN else Confidence.LIKELY, evidence)
        }

        // Keyboard and remote mode: nothing in it is the Flipper's alone, so it takes all three.
        val keyboardName = FLIPPER_HID_NAME_PREFIXES.any { n.startsWith(it) && n.length > it.length }
        if (HID_SERVICE in services && keyboardName && radio) {
            return Hit(
                Kind.FLIPPER_KEYBOARD, Confidence.LIKELY,
                listOf(
                    "offers itself as a Bluetooth keyboard",
                    "named \"$n\", a name the Flipper's keyboard mode takes",
                    "address begins $FLIPPER_RADIO_PREFIX, as the Flipper's radio chip's does"
                )
            )
        }

        if (named && addressed) return Hit(Kind.FLIPPER_ZERO, Confidence.LIKELY, evidence)
        if (ownBlock) return Hit(Kind.FLIPPER_ZERO, Confidence.LIKELY, evidence)
        // The name alone is anybody's to use, and the radio prefix alone is every product built on that chip.
        if (named) return Hit(Kind.FLIPPER_ZERO, Confidence.POSSIBLE, evidence)
        return null
    }

    /** [matchBle] on an [Advert]. */
    internal fun match(advert: Advert, address: String?): Hit? = matchBle(advert.name, advert.shorts, address)

    // ── Pairing requests ─────────────────────────────────────────────────────────

    /** Whose pairing pop-up a frame asks for. */
    enum class PromptFamily(val label: String) {
        APPLE_PAIRING("Apple accessory pairing"),
        APPLE_ACTION("Apple set-up prompts"),
        FAST_PAIR("Android Fast Pair"),
        SWIFT_PAIR("Windows Swift Pair"),
        SAMSUNG_SETUP("Samsung accessory set-up"),
    }

    /** One pairing request: whose it is, and which product it claims to be. */
    data class Prompt(val family: PromptFamily, val model: Int)

    private const val MICROSOFT = 0x0006
    private val SAMSUNG_BUDS = byteArrayOf(0x42, 0x09, 0x81.toByte(), 0x02, 0x14, 0x15, 0x03, 0x21, 0x01, 0x09)
    private val SAMSUNG_WATCH = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01, 0x01, 0xFF.toByte(), 0x00, 0x00, 0x43)

    /**
     * The pairing request in [advert], or null when it carries none. These are the
     * frames that make a phone or computer offer to pair, in the shapes their makers
     * document and the spam tools copy:
     *
     *  - Apple "proximity pairing" (type 0x07, 25 bytes: AirPods and Beats), where the
     *    model is bytes 3 and 4, and "nearby action" (type 0x0F: set up this Apple TV,
     *    share a Wi-Fi password), where the action is byte 3.
     *  - Google Fast Pair in pairing mode: service data under 0xFE2C that is exactly
     *    the three-byte model id (a device already paired sends a longer account frame).
     *  - Microsoft Swift Pair: beacon 0x03, scenario 0 to 2, then the reserved 0x80.
     *  - Samsung's earbud and watch set-up frames under company 0x0075.
     */
    internal fun promptOf(advert: Advert): Prompt? =
        advert.apple?.let(::applePrompt)
            ?: advert.fastPair?.let(::fastPairPrompt)
            ?: advert.manufacturer[MICROSOFT]?.let(::swiftPairPrompt)
            ?: advert.samsung?.let(::samsungPrompt)

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private fun applePrompt(a: ByteArray): Prompt? {
        if (a.size < 4) return null
        return when (u8(a[0])) {
            0x07 -> if (u8(a[1]) == 0x19 && a.size >= 5) Prompt(PromptFamily.APPLE_PAIRING, (u8(a[3]) shl 8) or u8(a[4])) else null
            0x0F -> Prompt(PromptFamily.APPLE_ACTION, u8(a[3]))
            else -> null
        }
    }

    private fun fastPairPrompt(f: ByteArray): Prompt? =
        if (f.size == 3) Prompt(PromptFamily.FAST_PAIR, (u8(f[0]) shl 16) or (u8(f[1]) shl 8) or u8(f[2])) else null

    private fun swiftPairPrompt(m: ByteArray): Prompt? {
        if (m.size < 3 || u8(m[0]) != 0x03) return null
        if (u8(m[1]) > 2 || u8(m[2]) != 0x80) return null
        // Swift Pair has no model: the name that follows stands in for one.
        return Prompt(PromptFamily.SWIFT_PAIR, m.copyOfRange(3, m.size).contentHashCode())
    }

    private fun samsungPrompt(s: ByteArray): Prompt? = when {
        startsWith(s, SAMSUNG_BUDS) && s.size >= 14 ->
            Prompt(PromptFamily.SAMSUNG_SETUP, (u8(s[10]) shl 16) or (u8(s[11]) shl 8) or u8(s[13]))
        startsWith(s, SAMSUNG_WATCH) && s.size >= 11 -> Prompt(PromptFamily.SAMSUNG_SETUP, u8(s[10]))
        else -> null
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    /**
     * Tells a flood of fake pairing requests from a room full of headphones.
     *
     * One pairing frame means nothing: AirPods send them all day. What a real device
     * never does is the two things the spam tools do. A real device keeps its address
     * for minutes and is heard again and again, and it is one product: the tools give
     * each packet a new address, or keep one address and claim to be a different
     * product every time. So this keeps the last [WINDOW_MS] of pairing requests and
     * raises one of two findings:
     *
     *  - **Many addresses, each heard once, from one place.** Addresses first heard at
     *    least [SETTLE_MS] ago (long enough for a real device to have spoken twice)
     *    that never spoke again. It takes [LIKELY_SINGLES] of them, making up
     *    [SINGLE_SHARE] of everything heard in that time, with their signal strengths
     *    within [RSSI_SPREAD_DB] dB of each other and no fainter than
     *    [MIN_MEDIAN_RSSI] dBm at the middle. The last two are the tests a crowd fails.
     *    Walk through a station and the headphones you pass are each heard once too,
     *    but from near and far, 30 dB apart; a flood comes from one transmitter. And
     *    the accessories at the very edge of range are each caught once and all
     *    equally faintly, where a tool near enough to put pop-ups on this phone is not
     *    faint.
     *  - **One address claiming to be many products**: [SHAPESHIFT_MODELS] different
     *    models from the same address. No real accessory changes what it is.
     *
     * Not thread-safe on its own: every method is synchronized, since scan results
     * arrive on the Bluetooth callback thread.
     */
    class PairingFlood {
        private class Seen(val first: Long, val rssi: Int, val family: PromptFamily, firstModel: Int) {
            var count = 1
            val models = HashSet<Int>(4).apply { add(firstModel) }
        }

        // Insertion order is first-heard order, so the oldest entries are at the front.
        private val seen = LinkedHashMap<String, Seen>()
        private var lastJudged = 0L

        /**
         * Records one pairing request and, at most once a second, judges the window.
         * Returns a finding while a flood is under way, null otherwise.
         */
        @Synchronized
        fun observe(prompt: Prompt, address: String, rssi: Int, now: Long): Hit? {
            val known = seen[address]
            if (known == null) {
                seen[address] = Seen(now, rssi, prompt.family, prompt.model)
                // A flood at full speed is 50 addresses a second; the oldest go first.
                if (seen.size > MAX_TRACKED) {
                    val oldest = seen.entries.iterator()
                    oldest.next()
                    oldest.remove()
                }
            } else {
                known.count++
                if (known.models.size < MODELS_KEPT) known.models.add(prompt.model)
                if (known.models.size >= SHAPESHIFT_MODELS) {
                    return Hit(
                        Kind.PAIRING_FLOOD,
                        if (known.models.size >= SHAPESHIFT_CERTAIN) Confidence.CERTAIN else Confidence.LIKELY,
                        listOf(
                            "one device has claimed to be ${known.models.size} different products (${known.family.label})",
                            "a real accessory is one product and stays that product"
                        )
                    )
                }
            }
            if (now - lastJudged < JUDGE_EVERY_MS) return null
            lastJudged = now
            return judge(now)
        }

        private fun judge(now: Long): Hit? {
            val oldest = seen.entries.iterator()
            while (oldest.hasNext()) {
                if (now - oldest.next().value.first > WINDOW_MS) oldest.remove() else break
            }
            var settled = 0
            val singles = ArrayList<Seen>()
            for (s in seen.values) {
                if (now - s.first < SETTLE_MS) continue
                settled++
                if (s.count == 1) singles += s
            }
            if (singles.size < LIKELY_SINGLES) return null
            if (singles.size < settled * SINGLE_SHARE) return null
            val strengths = singles.map { it.rssi }.sorted()
            if (strengths[strengths.size / 2] < MIN_MEDIAN_RSSI) return null
            val spread = strengths[(strengths.size * 9) / 10] - strengths[strengths.size / 10]
            if (spread > RSSI_SPREAD_DB) return null

            val byFamily = singles.groupingBy { it.family }.eachCount().entries.sortedByDescending { it.value }
            val evidence = listOf(
                "${singles.size} pairing requests in the last ${WINDOW_MS / 1000} seconds, each from an address heard only once",
                "all within $spread dB of each other, as from one transmitter",
                byFamily.joinToString(", ") { "${it.value} ${it.key.label}" }
            )
            return Hit(Kind.PAIRING_FLOOD, if (singles.size >= CERTAIN_SINGLES) Confidence.CERTAIN else Confidence.LIKELY, evidence)
        }

        /** Forgets everything: a new scan run starts clean. */
        @Synchronized
        fun reset() {
            seen.clear()
            lastJudged = 0L
        }

        companion object {
            /** How far back pairing requests are remembered. */
            const val WINDOW_MS = 30_000L
            /** How long an address is given to speak a second time before it counts as heard once. */
            const val SETTLE_MS = 5_000L
            const val JUDGE_EVERY_MS = 1_000L
            /** Addresses heard once that it takes to say a flood is likely, and to be sure. */
            const val LIKELY_SINGLES = 15
            const val CERTAIN_SINGLES = 40
            /** The part of everything heard that must have spoken only once. */
            const val SINGLE_SHARE = 0.8
            /** The widest gap, tenth to ninetieth percentile, between signal strengths from one transmitter. */
            const val RSSI_SPREAD_DB = 15
            /**
             * The faintest the middle packet of a flood may be. Below this the sender is
             * too far off to raise a pop-up here, and cannot be told from real accessories
             * at the edge of range, which are also heard once each.
             */
            const val MIN_MEDIAN_RSSI = -85
            /** Different products from one address before it is a tool and not an accessory. */
            const val SHAPESHIFT_MODELS = 4
            const val SHAPESHIFT_CERTAIN = 6
            private const val MODELS_KEPT = 8
            private const val MAX_TRACKED = 2_000
        }
    }
}
