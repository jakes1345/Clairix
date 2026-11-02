package app.clairix.nav

/** Where spoken directions are made, as far as the phone's speech engine says. */
enum class VoiceSource {
    /** The engine has not started yet. */
    UNKNOWN,

    /** A voice that synthesises on the phone is in use: nothing spoken leaves it. */
    ON_DEVICE,

    /** No such voice for the language: the engine may send what it reads out to its maker's servers. */
    NETWORK,

    /** The phone has no working speech engine; directions are shown, not spoken. */
    NONE,
}

/**
 * The voice spoken directions use, as a decision apart from Android's speech engine.
 *
 * What navigation reads out is the drive itself: street names, "plate reader ahead",
 * the incidents on the route. Some speech engines, and some voices of the common ones,
 * synthesise on their maker's servers, so the default voice may send all of that off
 * the phone. The navigator asks for a voice of the phone's language that works offline
 * and is installed: the engine's own default when that is one, else one for the same
 * country, else any of the language. Languages and countries are compared as ISO 639-2
 * and ISO 3166 alpha-3 codes, the form engines use most.
 */
object VoicePick {

    data class Candidate(
        val name: String,
        val language: String,
        val country: String,
        val needsNetwork: Boolean,
        val installed: Boolean,
    )

    /** The on-device voice to use, or null when there is none for [language]. */
    fun pick(candidates: List<Candidate>, language: String, country: String, defaultName: String?): Candidate? {
        val local = candidates.filter { !it.needsNetwork && it.installed && it.language.equals(language, ignoreCase = true) }
        if (local.isEmpty()) return null
        local.firstOrNull { it.name == defaultName }?.let { return it }
        return local.filter { it.country.equals(country, ignoreCase = true) }.minByOrNull { it.name }
            ?: local.minByOrNull { it.name }
    }
}
