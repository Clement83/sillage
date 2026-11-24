package io.github.clement83.sillage.map

import io.github.clement83.sillage.geo.Fix
import io.github.clement83.sillage.geo.GeoPoint
import io.github.clement83.sillage.geo.GradeChunk
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** GeoJSON line layers drawn over the base map: the recorded track, later the followed track. */
object TrackLayers {

    fun ensureLine(style: Style, id: String, color: Int, width: Float, below: String? = null) {
        if (style.getSource(id) != null) return
        style.addSource(GeoJsonSource(id))
        val layer = LineLayer("$id-line", id).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        if (below != null && style.getLayer(below) != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
    }

    /** A line coloured stretch by stretch from each feature's `color` property (grade colouring). */
    fun ensureGradedLine(style: Style, id: String, width: Float) {
        if (style.getSource(id) != null) return
        style.addSource(GeoJsonSource(id))
        val layer = LineLayer("$id-line", id).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        // Below everything drawn later (the recorded track, the position puck).
        style.addLayer(layer)
    }

    fun setGradedChunks(style: Style, id: String, chunks: List<GradeChunk>) {
        val features = chunks.filter { it.points.size >= 2 }.map { chunk ->
            Feature.fromGeometry(LineString.fromLngLats(chunk.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addStringProperty("color", GradeColors.hex(chunk.grade))
            }
        }
        style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun fitPoints(map: MapLibreMap, segments: List<List<GeoPoint>>, paddingPx: Int) =
        fit(map, segments.map { seg -> seg.map { Fix(0, it.lat, it.lon) } }, paddingPx)

    /** Round markers (key moments): white dot ringed with [color], drawn above the lines. */
    fun ensurePoints(style: Style, id: String, color: Int) {
        if (style.getSource(id) != null) return
        style.addSource(GeoJsonSource(id))
        style.addLayer(
            CircleLayer("$id-circle", id).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeColor(color),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    fun setPoints(style: Style, id: String, points: List<Pair<Double, Double>>) {
        val features = points.map { (lat, lon) -> Feature.fromGeometry(Point.fromLngLat(lon, lat)) }
        style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun setSegments(style: Style, id: String, segments: List<List<Fix>>) {
        val features = segments.filter { it.size >= 2 }.map { segment ->
            Feature.fromGeometry(LineString.fromLngLats(segment.map { Point.fromLngLat(it.lon, it.lat) }))
        }
        style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Moves the camera so the whole track fits, with [paddingPx] around it. */
    fun fit(map: MapLibreMap, segments: List<List<Fix>>, paddingPx: Int) {
        val points = segments.flatten()
        when {
            points.isEmpty() -> return
            points.size == 1 || points.all { it.lat == points[0].lat && it.lon == points[0].lon } ->
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 15.0))
            else -> {
                val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
            }
        }
    }
}
