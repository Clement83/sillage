package io.github.clement83.sillage.routing

import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import io.github.clement83.sillage.geo.GeoMath
import io.github.clement83.sillage.geo.GeoPoint
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt

/** A way found along the paths: the points to draw, its length and its climb. */
data class Route(val points: List<GeoPoint>, val distanceM: Double, val climbM: Double)

sealed interface RouteResult {
    data class Found(val route: Route) : RouteResult

    /** No downloaded segment covers the start or the end. */
    data object NoData : RouteResult

    /** BRouter found no way (no path nearby, too far, too long to compute). */
    data class Failed(val message: String) : RouteResult
}

/**
 * Routing on the phone with BRouter (#1733), pure JVM: [segmentsDir] holds the `.rd5` segments,
 * [profile] the `.brf` profile with `lookups.dat` next to it. One route at a time per instance.
 */
class Router(private val segmentsDir: File, private val profile: File) {

    fun route(from: GeoPoint, to: GeoPoint, maxRunningMs: Long = MAX_RUNNING_MS): RouteResult {
        val needed = setOf(SegmentTiles.nameOf(from.lat, from.lon), SegmentTiles.nameOf(to.lat, to.lon))
        if (needed.any { !File(segmentsDir, "$it.rd5").isFile }) return RouteResult.NoData
        if (GeoMath.distanceM(from.lat, from.lon, to.lat, to.lon) > MAX_STRAIGHT_M) return RouteResult.Failed("too far")
        val context = RoutingContext().apply { localFunction = profile.absolutePath }
        val engine = RoutingEngine(null, null, segmentsDir, listOf(node("from", from), node("to", to)), context)
        engine.quite = true
        engine.doRun(maxRunningMs)
        engine.errorMessage?.let { return RouteResult.Failed(it) }
        val track = engine.foundTrack ?: return RouteResult.Failed("no route")
        val points = track.nodes.map { n ->
            GeoPoint(
                lat = n.iLat / 1e6 - 90,
                lon = n.iLon / 1e6 - 180,
                alt = n.sElev.takeIf { it != Short.MIN_VALUE }?.let { it / 4.0 },
            )
        }
        if (points.size < 2) return RouteResult.Failed("no route")
        return RouteResult.Found(Route(points, track.distance.toDouble(), track.ascend.toDouble()))
    }

    private fun node(name: String, p: GeoPoint) = OsmNodeNamed().apply {
        this.name = name
        ilon = ((p.lon + 180) * 1e6).roundToInt()
        ilat = ((p.lat + 90) * 1e6).roundToInt()
    }

    companion object {
        const val MAX_RUNNING_MS = 20_000L

        /** Beyond this, a phone would take too long: the straight distance is shown instead. */
        const val MAX_STRAIGHT_M = 60_000.0
    }
}

/** BRouter's segments: one `.rd5` file per 5° × 5° square, named after its south-west corner. */
object SegmentTiles {
    private const val SIZE = 5

    /** The segment holding a point: `E5_N40` for 6.1° E, 43.2° N. */
    fun nameOf(lat: Double, lon: Double): String = name(base(lon), base(lat))

    /** Segments overlapping the box. */
    fun namesIn(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): Set<String> {
        val out = LinkedHashSet<String>()
        var lon = base(minLon)
        while (lon <= base(maxLon)) {
            var lat = base(minLat)
            while (lat <= base(maxLat)) {
                out += name(lon, lat)
                lat += SIZE
            }
            lon += SIZE
        }
        return out
    }

    private fun base(v: Double) = floor(v / SIZE).toInt() * SIZE

    private fun name(lon: Int, lat: Int) =
        (if (lon < 0) "W${-lon}" else "E$lon") + "_" + (if (lat < 0) "S${-lat}" else "N$lat")
}
