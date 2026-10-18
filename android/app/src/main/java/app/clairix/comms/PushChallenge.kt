package app.clairix.comms

/**
 * The check a relay pushes through a push endpoint on a host it does not
 * already trust: "clairix-verify:" and a one-time code, which the app echoes
 * back over its signed channel to show the endpoint really reaches this phone
 * (comms-worker/src/comms-logic.ts). Anything else that arrives by push is a
 * wake.
 */
object PushChallenge {
    const val PREFIX = "clairix-verify:"
    private val CODE = Regex("^[A-Za-z0-9_-]{43}$")

    /** The code in a push message, or null when the message is not a check. */
    fun codeFrom(content: ByteArray): String? {
        if (content.size > 256) return null
        val text = String(content, Charsets.UTF_8).trim()
        if (!text.startsWith(PREFIX)) return null
        return text.removePrefix(PREFIX).takeIf { CODE.matches(it) }
    }
}
