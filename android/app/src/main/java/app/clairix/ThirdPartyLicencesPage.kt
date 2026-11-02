package app.clairix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.ThirdPartyNotices.Component
import app.clairix.ThirdPartyNotices.Origin
import app.clairix.nav.MapTiles
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.EmptyState
import app.clairix.ui.components.ListGroup
import app.clairix.ui.components.ListRow
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > About > Third-party licences: every library, native component
 * and Rust crate in this build, and the full text of each licence they are
 * used under. The list is the one the build wrote into the APK
 * ([ThirdPartyNotices.ASSET]), so it is the list for this very build.
 */
@Composable
internal fun ThirdPartyLicencesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var notices by remember { mutableStateOf<ThirdPartyNotices?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(ThirdPartyNotices.ASSET).use { ThirdPartyNotices.parse(it.readBytes().toString(Charsets.UTF_8)) }
            }.getOrNull()
        }
        if (loaded == null) unreadable = true else notices = loaded
    }

    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val list = notices
    val open = list?.components?.firstOrNull { keyOf(it) == openKey }
    if (list != null && open != null) {
        BackHandler { openKey = null }
        ComponentLicences(list, open, onBack = { openKey = null })
        return
    }

    ToolPageList(title = "Third-party licences", onBack = onBack) {
        when {
            unreadable -> item(key = "§unreadable") {
                EmptyState(
                    title = "The list could not be read",
                    body = "This build's licence list is missing or damaged. The licences are also listed in " +
                        "THIRD-PARTY-NOTICES.md in Clairix's source code, at github.com/jakes1345/Clairix.",
                    tone = Tone.Caution
                )
            }
            list == null -> item(key = "§loading") { LoadingBlock("Reading the licences") }
            else -> {
                item(key = "§intro") {
                    ClairixCard(spacing = 6.dp) {
                        Text(
                            "Clairix is built on the open-source work below, each used under its own licence. " +
                                "Tap one to read its licence in full. This list was made when this copy of the app " +
                                "was built, from the licences the libraries publish and the licence files of the Rust crates.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                }
                section(list, Origin.ANDROID, "Android libraries") { openKey = keyOf(it) }
                section(list, Origin.NATIVE, "Compiled into those libraries") { openKey = keyOf(it) }
                section(list, Origin.RUST, "Rust crates in the encryption library") { openKey = keyOf(it) }
            }
        }
    }
}

/**
 * The credits under Settings > About > License: the map data's ODbL credit,
 * naming the tile server the maps use now (the full edition, which has maps),
 * and the button that opens [ThirdPartyLicencesPage].
 */
@Composable
internal fun AboutCredits(onOpenLicences: () -> Unit) {
    val colors = ClairixTheme.colors
    if (BuildConfig.FULL_ACCESS) {
        val tiles by MapTiles.config.collectAsStateWithLifecycle()
        Text(
            "Map data © OpenStreetMap contributors, available under the Open Database Licence (ODbL) " +
                "at openstreetmap.org/copyright. The map's tiles come from ${tiles.name}'s tile server " +
                "(${tiles.attribution}), and an online place search from OpenStreetMap's Nominatim.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSubtle
        )
    }
    Text(
        "The libraries Clairix is built with keep their own licences: Android libraries, the code " +
            "compiled into them, and the Rust crates of the encryption library.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textSubtle
    )
    SecondaryButton("Third-party licences", onClick = onOpenLicences, modifier = Modifier.fillMaxWidth())
}

private fun keyOf(c: Component): String = "${c.origin}:${c.id}:${c.version.orEmpty()}"

private fun LazyListScope.section(list: ThirdPartyNotices, origin: Origin, title: String, onOpen: (Component) -> Unit) {
    val components = list.from(origin)
    if (components.isEmpty()) return
    item(key = "§hdr-$origin") { SectionHeader(title, supporting = components.size.toString()) }
    items(components, key = { keyOf(it) }) { c ->
        ListGroup {
            ListRow(
                title = c.name + (c.version?.let { " $it" } ?: ""),
                subtitle = list.licencesOf(c).joinToString(" · ") { it.name },
                onClick = { onOpen(c) },
                onClickLabel = "Read the licence"
            )
        }
    }
}

/** One component: who publishes it, where it is, and each of its licences in full. */
@Composable
private fun ComponentLicences(list: ThirdPartyNotices, c: Component, onBack: () -> Unit) {
    val colors = ClairixTheme.colors
    ToolPageList(title = c.name, onBack = onBack) {
        item(key = "§about") {
            ClairixCard(spacing = 4.dp) {
                Text(c.name + (c.version?.let { " $it" } ?: ""), style = MaterialTheme.typography.titleSmall, color = colors.text)
                if (c.id != c.name) Text(c.id, style = MaterialTheme.typography.mono, color = colors.textMuted)
                c.by?.let { Text("By $it", style = MaterialTheme.typography.bodyMedium, color = colors.textSubtle) }
                c.inside?.let { Text("Compiled into $it", style = MaterialTheme.typography.bodyMedium, color = colors.textSubtle) }
                c.website?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSubtle) }
                c.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSubtle) }
            }
        }
        for (licence in list.licencesOf(c)) {
            item(key = "§lic-${licence.key}") {
                ClairixCard(spacing = 6.dp) {
                    Text(licence.name, style = MaterialTheme.typography.titleSmall, color = colors.text)
                    licence.url?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSubtle) }
                    Text(
                        licence.text ?: "The full text is at the address above.",
                        style = if (licence.text != null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted
                    )
                }
            }
        }
    }
}
