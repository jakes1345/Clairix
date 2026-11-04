package app.clairix.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.R
import app.clairix.comms.RelayDirectory
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.Notice
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import org.osmdroid.views.MapView

// What every map in the app draws over osmdroid's view: the data credit, the
// notice when the tile server stops serving tiles, and the switch to a new
// server when a signed tile setting names one.

/**
 * The map's credit: the tile setting's attribution, OpenStreetMap's licence
 * credit, which the ODbL and the OpenStreetMap tile usage policy require on
 * the map itself, visible and not under other controls. A tap opens the
 * licence page ([TileConfig.attributionUrl]).
 */
@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    val config by MapTiles.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = ClairixTheme.colors
    Text(
        config.attribution,
        style = MaterialTheme.typography.labelSmall,
        color = c.text,
        maxLines = 1,
        modifier = modifier
            .background(c.surfaceRaised.copy(alpha = 0.88f), ClairixShapes.Tag)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.drive_open_the_map_data_licence)) {
                openLink(context, config.attributionUrl)
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * "Map tiles unavailable", shown while the tile server refuses or fails every
 * tile ([MapTiles.outage]); [consequence] says what the screen still shows.
 * Nothing while tiles are coming.
 */
@Composable
fun TilesUnavailableNotice(consequence: String, modifier: Modifier = Modifier) {
    val outage by MapTiles.outage.collectAsStateWithLifecycle()
    val config by MapTiles.config.collectAsStateWithLifecycle()
    val now = outage ?: return
    Notice(Tone.Caution, now.describe(config.name) + " " + consequence, modifier = modifier, title = stringResource(R.string.drive_map_tiles_unavailable))
}

/**
 * Keeps [mapView] on the server [MapTiles.config] names, moving it when a
 * newer signed setting arrives, and asks the `relays` release for one at most
 * once a day while a map is shown ([RelayDirectory.refreshTilesIfStale]).
 */
@Composable
fun FollowTileServer(mapView: MapView) {
    val context = LocalContext.current
    val config by MapTiles.config.collectAsStateWithLifecycle()
    LaunchedEffect(mapView, config) {
        val showing = (mapView.tileProvider.tileSource as? ConfiguredTileSource)?.config
        if (showing !== config) mapView.setTileSource(MapTiles.tileSource(config))
    }
    LaunchedEffect(Unit) { RelayDirectory.refreshTilesIfStale(context) }
}

/** Opens [url] in the browser, or says no app on the phone can. */
internal fun openLink(context: Context, url: String) {
    val started = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!started) Toast.makeText(context, "No app on this phone can open $url", Toast.LENGTH_LONG).show()
}
