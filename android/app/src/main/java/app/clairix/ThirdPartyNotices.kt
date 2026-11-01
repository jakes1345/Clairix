package app.clairix

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The third-party notices the APK carries: assets/third_party_licences.json,
 * written when the app is built (thirdPartyNotices in app/build.gradle.kts)
 * from the licences the Android libraries publish, cargo-about's list of the
 * Rust crates in the encryption library, and app/notices. Every component in
 * the build and the full text of every licence they are used under.
 */
class ThirdPartyNotices(val components: List<Component>, val licences: Map<String, Licence>) {

    enum class Origin { ANDROID, NATIVE, RUST }

    data class Component(
        val origin: Origin,
        val name: String,
        val id: String,
        val version: String?,
        /** Who publishes it, as its metadata says. */
        val by: String?,
        val website: String?,
        /** For code compiled into another library: that library's id. */
        val inside: String?,
        val note: String?,
        /** Keys into [ThirdPartyNotices.licences]. */
        val licences: List<String>,
    )

    class Licence(
        val key: String,
        /** Its SPDX identifier, or the project's name for a licence SPDX has none for. */
        val id: String,
        val name: String,
        val url: String?,
        /** The full text; null only for terms shown by name and link alone. */
        val text: String?,
    )

    /** The licences [component] is used under, in its order. */
    fun licencesOf(component: Component): List<Licence> = component.licences.mapNotNull { licences[it] }

    /** The components from [origin], in the asset's order. */
    fun from(origin: Origin): List<Component> = components.filter { it.origin == origin }

    companion object {
        const val ASSET = "third_party_licences.json"
        const val FORMAT = "clairix-third-party-licences"
        const val VERSION = 1

        /** The notices in [text], or an [IllegalArgumentException] saying what is wrong with them. */
        fun parse(text: String): ThirdPartyNotices {
            val doc = try { JSONObject(text) } catch (e: JSONException) { throw IllegalArgumentException("not JSON", e) }
            require(doc.optString("format") == FORMAT) { "not Clairix's third-party notices" }
            require(doc.opt("version") == VERSION) { "notices version ${doc.opt("version")} is not one this app reads" }
            val licenceDoc = requireNotNull(doc.optJSONObject("licences")) { "no licences" }
            val licences = LinkedHashMap<String, Licence>()
            for (key in licenceDoc.keys().asSequence().toList()) {
                val o = requireNotNull(licenceDoc.optJSONObject(key)) { "licence $key: not an object" }
                licences[key] = Licence(
                    key = key,
                    id = requireNotNull(text(o, "id")) { "licence $key: no id" },
                    name = requireNotNull(text(o, "name")) { "licence $key: no name" },
                    url = text(o, "url"),
                    text = text(o, "text"),
                )
            }
            val arr = requireNotNull(doc.optJSONArray("components")) { "no components" }
            val components = ArrayList<Component>(arr.length())
            for (i in 0 until arr.length()) {
                val o = requireNotNull(arr.optJSONObject(i)) { "component ${i + 1}: not an object" }
                val name = requireNotNull(text(o, "name")) { "component ${i + 1}: no name" }
                val origin = when (o.optString("origin")) {
                    "android" -> Origin.ANDROID
                    "native" -> Origin.NATIVE
                    "rust" -> Origin.RUST
                    else -> throw IllegalArgumentException("$name: unknown origin ${o.opt("origin")}")
                }
                val keys = strings(o.optJSONArray("licences"))
                require(keys.isNotEmpty()) { "$name: no licence" }
                for (k in keys) require(k in licences) { "$name: licence $k is not in the list" }
                components += Component(
                    origin = origin,
                    name = name,
                    id = text(o, "id") ?: name,
                    version = text(o, "version"),
                    by = text(o, "by"),
                    website = text(o, "website"),
                    inside = text(o, "in"),
                    note = text(o, "note"),
                    licences = keys,
                )
            }
            return ThirdPartyNotices(components, licences)
        }

        private fun text(o: JSONObject, field: String): String? = (o.opt(field) as? String)?.takeIf { it.isNotBlank() }

        private fun strings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { (arr.opt(it) as? String)?.takeIf { s -> s.isNotBlank() } }
        }
    }
}
