package dev.yk.offercollector.ui

import android.os.Bundle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonObject
import dev.yk.offercollector.core.OfferAnalytics
import dev.yk.offercollector.core.ShiftRoute
import java.time.Instant
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val COLOR_DELIVERING = "#1d6fb8"
private const val COLOR_BETWEEN = "#8a8a85"
private const val COLOR_PICKUP = "#e8590c"
private const val COLOR_DROPOFF = "#2f9e44"

private fun hm(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(OfferAnalytics.ZONE).let { z -> String.format(java.util.Locale.US, "%02d:%02d", z.hour, z.minute) } } ?: "—"

/** Shift route tab: pick a shift, see where you drove (after the shift — nothing here is shown while driving). */
@Composable
fun RouteSection(vm: MainViewModel) {
    val shifts by vm.shifts.collectAsStateWithLifecycle()
    val choices = remember(shifts) { shifts.filter { (it.endedAtUtc ?: it.lastAliveUtc) - it.startedAtUtc >= 5 * 60_000 }.sortedByDescending { it.startedAtUtc }.take(10) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val shiftId = selected ?: choices.firstOrNull()?.id
    val route by produceState<ShiftRoute.Route?>(null, shiftId) { value = shiftId?.let { vm.loadRoute(it) } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (choices.isEmpty()) {
            AppCard { Text("עדיין אין משמרת עם מיקום. מסלול יופיע אחרי המשמרת הראשונה.") }
            return@Column
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { s ->
                val d = Instant.ofEpochMilli(s.startedAtUtc).atZone(OfferAnalytics.ZONE)
                FilterChip(selected = s.id == shiftId, onClick = { selected = s.id }, label = { Text("${d.dayOfMonth}.${d.monthValue} ${hm(s.startedAtUtc)}") })
            }
        }
        val r = route
        AppCard {
            if (r == null) Text("טוען…")
            else {
                CardTitle("מסלול ${hm(r.startMs)}–${hm(r.endMs)}")
                Text(
                    String.format(java.util.Locale.US, "%.1f ק״מ סה״כ · %.1f ק״מ במשלוחים · %d נקודות", r.km, r.deliveringKm, r.points),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (r.precision) {
                    ShiftRoute.Precision.PRECISE -> Text("מסלול מדויק (~5 מ׳).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ShiftRoute.Precision.COARSE -> Text(
                        "מסלול גס: למשמרת הזו נשמרו רק ריבועים של ~500 מ׳, לכן הקו קופץ בין מרכזי ריבועים ולא עובר ברחובות. מסלול מדויק יישמר מהמשמרת הבאה.",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFE8590C),
                    )
                    ShiftRoute.Precision.NONE -> Text("אין מספיק נקודות מיקום למשמרת הזו.")
                }
            }
        }
        if (r != null && r.precision != ShiftRoute.Precision.NONE) {
            RouteMapView(r)
            AppCard {
                Legend(COLOR_DELIVERING, "בזמן משלוח (מקבלה ועד המסירה האחרונה בשרשרת)")
                Legend(COLOR_BETWEEN, "בין משלוחים")
                Legend(COLOR_PICKUP, "איסוף (משוער: כשהופיע מסך המסירה)")
                Legend(COLOR_DROPOFF, "מסירה (נצפתה: החלקת «заказ доставлен»)")
                Legend("#000000", "התחלה / סוף")
            }
        }
    }
}

@Composable
private fun Legend(hex: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("●", color = Color(android.graphics.Color.parseColor(hex)))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun lines(r: ShiftRoute.Route) = FeatureCollection.fromFeatures(
    r.segments.map { s ->
        Feature.fromGeometry(LineString.fromLngLats(s.points.map { Point.fromLngLat(it.lon, it.lat) }), JsonObject().apply { addProperty("delivering", s.delivering) })
    },
)

private fun marks(r: ShiftRoute.Route) = FeatureCollection.fromFeatures(
    r.markers.map { m -> Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat), JsonObject().apply { addProperty("kind", m.kind.name) }) },
)

private fun fit(map: MapLibreMap, r: ShiftRoute.Route) {
    val pts = r.segments.flatMap { it.points }
    if (pts.size < 2) return
    val b = LatLngBounds.Builder()
    pts.forEach { b.include(LatLng(it.lat, it.lon)) }
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 60))
}

/** MapLibre + OpenFreeMap tiles (tiles in, nothing out). Line = where you drove; dots = pickups / drop-offs. */
@Composable
private fun RouteMapView(r: ShiftRoute.Route) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    val view = remember {
        MapLibre.getInstance(ctx)
        // TextureView: the map sits inside a scrolling screen (SurfaceView goes blank while scrolling).
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
                style.addSource(GeoJsonSource("route", lines(r)))
                style.addSource(GeoJsonSource("marks", marks(r)))
                val coarse = r.precision == ShiftRoute.Precision.COARSE
                style.addLayer(
                    LineLayer("route-line", "route").withProperties(
                        PropertyFactory.lineColor(
                            Expression.switchCase(
                                Expression.get("delivering"), Expression.color(android.graphics.Color.parseColor(COLOR_DELIVERING)),
                                Expression.color(android.graphics.Color.parseColor(COLOR_BETWEEN)),
                            ),
                        ),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        *(if (coarse) arrayOf(PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f))) else emptyArray()),
                    ),
                )
                style.addLayer(
                    CircleLayer("marks-dot", "marks").withProperties(
                        PropertyFactory.circleRadius(Expression.match(Expression.get("kind"), Expression.literal(6f), Expression.stop("START", 7f), Expression.stop("END", 7f))),
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get("kind"), Expression.color(android.graphics.Color.BLACK),
                                Expression.stop("PICKUP", Expression.color(android.graphics.Color.parseColor(COLOR_PICKUP))),
                                Expression.stop("DROPOFF", Expression.color(android.graphics.Color.parseColor(COLOR_DROPOFF))),
                            ),
                        ),
                        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                fit(map, r)
                mapRef = map
                styleRef = style
            }
        }
    }
    // Another shift selected → replace the data and refit.
    LaunchedEffect(r, styleRef) {
        styleRef?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(lines(r))
        styleRef?.getSourceAs<GeoJsonSource>("marks")?.setGeoJson(marks(r))
        mapRef?.let { fit(it, r) }
    }
    AndroidView({ view }, Modifier.fillMaxWidth().height(460.dp).clip(CardShape))
}
