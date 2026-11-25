package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.geo.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON of singletrack's companion API (`/api/app/…`, singletrack docs/companion-app-api.md). Every field
 * the app can live without is optional, so a server adding or dropping one does not break the app.
 */
object AppApi {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
}

@Serializable
data class Meeting(val lat: Double? = null, val lon: Double? = null, val label: String? = null)

@Serializable
data class ProfilePoint(val distKm: Double, val ele: Double)

@Serializable
data class Participant(
    val name: String? = null,
    /** `pending` | `yes` | `maybe` | `no`. */
    val status: String? = null,
    val isMe: Boolean = false,
    val isOrganizer: Boolean = false,
)

@Serializable
data class OutingTrack(
    val name: String? = null,
    /** GeoJSON LineString or MultiLineString, positions `[lon, lat]` or `[lon, lat, ele]`. */
    val geometry: JsonObject? = null,
    val distanceM: Double? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    /** `green` | `blue` | `red` | `black`. */
    val difficulty: String? = null,
    val loop: Boolean? = null,
    val attribution: String? = null,
    val license: String? = null,
    val profile: List<ProfilePoint> = emptyList(),
) {
    /** The route as segments of points, empty when the geometry is missing or unreadable. */
    fun segments(): List<List<GeoPoint>> = geometry?.let(::segmentsOf).orEmpty()
}

/**
 * An outing, as listed (`GET /api/app/outings`) or in detail (`/outings/<ref>`, `/i/<token>`). The
 * detail-only fields are null in a list.
 */
@Serializable
data class Outing(
    val id: String,
    /** Organiser only. */
    val slug: String? = null,
    /** `organizer` | `guest`. */
    val role: String = ROLE_GUEST,
    val title: String = "",
    /** ISO 8601, UTC. */
    val startsAt: String,
    /** `planned` | `cancelled` | `done`. */
    val status: String? = null,
    val cancelled: Boolean = false,
    val done: Boolean = false,
    val closed: Boolean = false,
    val meeting: Meeting? = null,
    val distanceM: Double? = null,
    val elevationGainM: Double? = null,
    /** `balade` | `sportif` | `enduro`. */
    val level: String? = null,
    /** `yes` | `no` | `only`. */
    val ebike: String? = null,
    val organizerName: String? = null,
    val description: String? = null,
    val maxParticipants: Int? = null,
    /** `/s/<slug>` for the organiser, null for a guest. */
    val webPath: String? = null,
    /** `yes` | `maybe` | `no` | `pending`, null for the organiser. */
    val myAnswer: String? = null,
    val yesCount: Int? = null,
    val participants: List<Participant>? = null,
    val track: OutingTrack? = null,
) {
    val startsAtMs: Long get() = runCatching { java.time.Instant.parse(startsAt).toEpochMilli() }.getOrDefault(0L)

    companion object {
        const val ROLE_ORGANIZER = "organizer"
        const val ROLE_GUEST = "guest"
    }
}

@Serializable
data class OutingList(val outings: List<Outing> = emptyList())

@Serializable
data class PairRequest(val code: String, val deviceName: String)

@Serializable
data class PairResponse(val token: String)

@Serializable
data class ApiError(val error: String? = null)

/** `POST /api/app/rides` (#1765): the private solo outing a ride became. */
@Serializable
data class SoloOutingResponse(val slug: String, val url: String? = null)

/** GeoJSON LineString / MultiLineString to segments; unreadable positions are skipped. */
internal fun segmentsOf(geometry: JsonObject): List<List<GeoPoint>> {
    val coordinates = geometry["coordinates"] as? JsonArray ?: return emptyList()
    val lines = when (geometry["type"]?.jsonPrimitive?.content) {
        "LineString" -> listOf(coordinates)
        "MultiLineString" -> coordinates.mapNotNull { it as? JsonArray }
        else -> return emptyList()
    }
    return lines.map { line ->
        line.mapNotNull { position ->
            val values = (position as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull } ?: return@mapNotNull null
            val lon = values.getOrNull(0) ?: return@mapNotNull null
            val lat = values.getOrNull(1) ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            GeoPoint(lat, lon, values.getOrNull(2))
        }
    }.filter { it.size >= 2 }
}
