package app.clairix

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.clairix.ui.components.ClairixTopBar
import app.clairix.ui.theme.ClairixTheme
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * One of the owner's places on the map, full screen, when she asks to see it.
 * The Places cards on the Patterns page name a place and no longer print its
 * coordinates; this is where she sees where it is. The tiles come from
 * OpenStreetMap, as on the Map tab, which sees this phone's IP address and the
 * area shown; nothing is fetched until she opens it.
 */
@Composable
internal fun PlaceMapDialog(label: String, lat: Double, lon: Double, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = ClairixTheme.colors
        Column(Modifier.fillMaxSize().background(c.base)) {
            ClairixTopBar(title = label, onBack = onDismiss)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PlaceMapView(label = label, lat = lat, lon = lon)
            }
            Text(
                "Map © OpenStreetMap contributors",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSubtle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PlaceMapView(label: String, lat: Double, lon: Double) {
    val context = LocalContext.current
    val fill = ClairixTheme.colors.accent.toArgb()
    val ring = ClairixTheme.colors.text.toArgb()
    val mapView = remember {
        app.clairix.nav.MapTiles.configure(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setTilesScaledToDpi(true)
            setMultiTouchControls(true)
            isFlingEnabled = true
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(lat, lon))
            overlays.add(
                Marker(this).apply {
                    position = GeoPoint(lat, lon)
                    title = label
                    icon = placeDot(fill, ring)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
            )
        }
    }
    // Follows the lifecycle like the Map tab's view, and lets go of its tile
    // threads and caches when the dialog closes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

/** A filled dot with a ring, for the place: the Map tab's marker style. */
private fun placeDot(fill: Int, ring: Int, sizeDp: Int = 22): BitmapDrawable {
    val px = (sizeDp * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill
        style = Paint.Style.FILL
    }
    canvas.drawCircle(px / 2f, px / 2f, px / 2f - 1f, paint)
    paint.color = ring
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = px * 0.18f
    canvas.drawCircle(px / 2f, px / 2f, px / 2f - 2f, paint)
    return BitmapDrawable(Resources.getSystem(), bitmap)
}
