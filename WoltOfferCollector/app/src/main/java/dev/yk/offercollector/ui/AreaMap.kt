package dev.yk.offercollector.ui

import android.os.Bundle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.JsonObject
import dev.yk.offercollector.core.LocationGrid
import kotlin.math.cos
import kotlin.math.hypot
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** One ~500 m area to draw: value null = no data for the chosen metric (grey, not ranked). */
data class MapArea(val cellId: String, val value: Double?, val label: String)

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val SRC = "areas"
private const val PLACE_NAME_MAX_KM = 0.8
private val PLACE_CLASSES = setOf("neighbourhood", "quarter", "suburb", "village", "town", "city")
/** One hue, light → dark, same ramp as the web engine (courier-side layers). */
private val RAMP = listOf("#cfe2f3", "#9cc3e6", "#5aa2e6", "#1d6fb8", "#0b3f73")
/** A second hue for the pickup (business) layer, so the two are never confused. */
private val RAMP_PICKUP = listOf("#fde2cc", "#f9b98a", "#f08a4b", "#d9480f", "#8a2a05")

private fun features(areas: List<MapArea>, selected: String?, pickup: Boolean = false): FeatureCollection {
    val vals = areas.mapNotNull { it.value }
    val lo = vals.minOrNull() ?: 0.0
    val hi = vals.maxOrNull() ?: 0.0
    return FeatureCollection.fromFeatures(
        areas.mapNotNull { a ->
            val ring = LocationGrid.polygon(a.cellId) ?: return@mapNotNull null
            val props = JsonObject().apply {
                addProperty("cellId", a.cellId)
                addProperty("label", a.label)
                addProperty("hasValue", a.value != null)
                addProperty("t", a.value?.let { if (hi > lo) (it - lo) / (hi - lo) else 0.5 } ?: 0.0)
                addProperty("selected", a.cellId == selected)
                addProperty("pickup", pickup)
            }
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring.map { Point.fromLngLat(it.first, it.second) })), props)
        },
    )
}

private fun fit(map: MapLibreMap, areas: List<MapArea>) {
    val pts = areas.mapNotNull { LocationGrid.polygon(it.cellId) }.flatten()
    if (pts.isEmpty()) return
    val b = LatLngBounds.Builder()
    pts.forEach { b.include(LatLng(it.second, it.first)) }
    // A single 500 m square: zoom 14 shows its streets around it, and loads the tiles that carry
    // neighbourhood labels (used for the area's name). Several squares: fit them, at most zoom 14.
    val bounds = b.build()
    if (areas.size == 1) map.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 14.0))
    else {
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        if (map.cameraPosition.zoom > 14.0) map.moveCamera(CameraUpdateFactory.zoomTo(14.0))
    }
}

/** Place names from the map's own labels (already-downloaded tiles; no geocoding service). */
private fun placeNames(style: Style, areas: List<MapArea>): Map<String, String> {
    val src = style.getSourceAs<VectorSource>("openmaptiles") ?: return emptyMap()
    val places = runCatching { src.querySourceFeatures(arrayOf("place"), null) }.getOrDefault(emptyList()).mapNotNull { f ->
        val cls = f.getStringProperty("class") ?: return@mapNotNull null
        if (cls !in PLACE_CLASSES) return@mapNotNull null
        val name = (if (f.hasProperty("name:he")) f.getStringProperty("name:he") else null) ?: f.getStringProperty("name") ?: return@mapNotNull null
        val p = f.geometry() as? Point ?: return@mapNotNull null
        Triple(name, p, if (cls in setOf("neighbourhood", "quarter", "suburb")) 0 else 1)
    }
    return areas.mapNotNull { a ->
        val ring = LocationGrid.polygon(a.cellId) ?: return@mapNotNull null
        val lon = (ring[0].first + ring[2].first) / 2
        val lat = (ring[0].second + ring[2].second) / 2
        places.map { (n, p, rank) -> Triple(n, hypot((p.longitude() - lon) * 111.32 * cos(Math.toRadians(lat)), (p.latitude() - lat) * 111.32), rank) }
            .filter { it.second <= PLACE_NAME_MAX_KM }
            .minWithOrNull(compareBy({ it.third }, { it.second }))
            ?.let { a.cellId to it.first }
    }.toMap()
}

/**
 * Real map (MapLibre + OpenFreeMap tiles). The only network use in the app: tiles in, nothing out.
 * Areas are the real ~500 m squares where the courier was; colour = chosen metric; tap = select.
 */
@Composable
fun AreaMap(areas: List<MapArea>, selected: String?, onSelect: (String) -> Unit, onPlaceNames: (Map<String, String>) -> Unit, pickupLayer: Boolean = false) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    val names by rememberUpdatedState(onPlaceNames)
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    val view = remember {
        MapLibre.getInstance(ctx)
        // TextureView mode: the map lives inside a scrolling screen; the default SurfaceView goes blank
        // while the page moves and ignores the rounded clip.
        MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)).apply { onCreate(Bundle()) }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            view.onPause(); view.onStop(); view.onDestroy()
        }
    }

    LaunchedEffect(view) {
        view.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT)) { style ->
                style.addSource(GeoJsonSource(SRC, features(areas, selected, pickupLayer)))
                val stops = RAMP.mapIndexed { i, c -> Expression.stop(i / (RAMP.size - 1.0), Expression.color(android.graphics.Color.parseColor(c))) }.toTypedArray()
                val stopsPickup = RAMP_PICKUP.mapIndexed { i, c -> Expression.stop(i / (RAMP_PICKUP.size - 1.0), Expression.color(android.graphics.Color.parseColor(c))) }.toTypedArray()
                style.addLayer(
                    FillLayer("areas-fill", SRC).withProperties(
                        PropertyFactory.fillColor(
                            Expression.switchCase(
                                Expression.all(Expression.get("hasValue"), Expression.get("pickup")), Expression.interpolate(Expression.linear(), Expression.get("t"), *stopsPickup),
                                Expression.get("hasValue"), Expression.interpolate(Expression.linear(), Expression.get("t"), *stops),
                                Expression.color(android.graphics.Color.parseColor("#9a9a94")),
                            ),
                        ),
                        PropertyFactory.fillOpacity(Expression.switchCase(Expression.get("hasValue"), Expression.literal(0.6f), Expression.literal(0.35f))),
                    ),
                )
                style.addLayer(
                    LineLayer("areas-line", SRC).withProperties(
                        PropertyFactory.lineColor(Expression.switchCase(Expression.get("selected"), Expression.color(android.graphics.Color.parseColor("#e8590c")), Expression.color(if (dark) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY))),
                        PropertyFactory.lineWidth(Expression.switchCase(Expression.get("selected"), Expression.literal(3.5f), Expression.literal(1f))),
                    ),
                )
                style.addLayer(
                    SymbolLayer("areas-label", SRC).withProperties(
                        PropertyFactory.textField(Expression.get("label")),
                        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textColor(if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK),
                        PropertyFactory.textHaloColor(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE),
                        PropertyFactory.textHaloWidth(1.5f),
                    ),
                )
                fit(map, areas)
                map.addOnMapClickListener { ll ->
                    val hit = map.queryRenderedFeatures(map.projection.toScreenLocation(ll), "areas-fill").firstOrNull()
                    hit?.getStringProperty("cellId")?.let { select(it); true } ?: false
                }
                view.addOnDidBecomeIdleListener { names(placeNames(style, areas)) }
                mapRef = map
                styleRef = style
            }
        }
    }

    // Data / selection changed → update the source only.
    LaunchedEffect(areas, selected, styleRef, pickupLayer) {
        styleRef?.getSourceAs<GeoJsonSource>(SRC)?.setGeoJson(features(areas, selected, pickupLayer))
    }
    LaunchedEffect(areas.map { it.cellId }, mapRef) { mapRef?.let { fit(it, areas) } }

    AndroidView({ view }, Modifier.fillMaxWidth().height(380.dp).clip(CardShape))
}
