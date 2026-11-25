package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.data.ImportedPointEntity
import io.github.clement83.sillage.data.ImportedTrackDao
import io.github.clement83.sillage.data.ImportedTrackEntity
import io.github.clement83.sillage.data.ImportedWaypointEntity
import io.github.clement83.sillage.data.OutingDao
import io.github.clement83.sillage.data.OutingEntity
import io.github.clement83.sillage.geo.TrackShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What opening a scanned or tapped link gave. */
sealed interface OpenResult {
    /** [stale]: the server could not be reached, the copy kept from an earlier visit is shown. */
    data class Opened(val outingId: Long, val stale: Boolean) : OpenResult

    /** Unknown, revoked, or not visible (singletrack answers 404 for all three). */
    data object NotFound : OpenResult

    /** An organiser page (`/s/<slug>`) is readable in the app only with the organiser's paired account. */
    data object NeedsAccount : OpenResult

    /** No network (or server down) and never opened before. */
    data object Offline : OpenResult

    /** A group link: joining happens on the website, which then gives the personal link. */
    data object JoinOnWebsite : OpenResult
}

/** What a refresh gave; the cached copy stays in every case. */
enum class RefreshResult { UPDATED, NOT_FOUND, OFFLINE, NO_ACCESS, UNAUTHORIZED }

/**
 * singletrack outings in the app (#1727, #1728): fetched from the server, kept in Room to be read
 * offline, and turned into an imported track when followed.
 */
class OutingRepository(
    private val dao: OutingDao,
    private val tracks: ImportedTrackDao,
    private val accounts: AccountStore,
    private val client: SingletrackClient,
    /** Imported track followed by the recording in progress, if any: never deleted under it. */
    private val followedTrackId: () -> Long?,
) {
    private val writeLock = Mutex()
    private val _accountRevoked = MutableStateFlow(false)

    /** singletrack answered 401: the account was unpaired there. The UI says so once, then [consumeRevoked]. */
    val accountRevoked: StateFlow<Boolean> = _accountRevoked.asStateFlow()

    val outings: Flow<List<OutingEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<OutingEntity?> = dao.observe(id)

    fun consumeRevoked() {
        _accountRevoked.value = false
    }

    suspend fun open(link: SingletrackLink): OpenResult = when (link) {
        is SingletrackLink.Invite -> when (val r = client.guestOuting(link.server, link.token)) {
            is ApiResult.Ok -> OpenResult.Opened(save(link.server, r.value, detail = true, inviteToken = link.token), stale = false)
            ApiResult.NotFound -> OpenResult.NotFound
            else -> dao.findByInvite(link.server, link.token)?.let { OpenResult.Opened(it.id, stale = true) } ?: OpenResult.Offline
        }
        is SingletrackLink.Outing -> openAsOrganizer(link)
        is SingletrackLink.Join -> OpenResult.JoinOnWebsite
        is SingletrackLink.Pair -> OpenResult.NotFound
    }

    private suspend fun openAsOrganizer(link: SingletrackLink.Outing): OpenResult {
        val cached = dao.findBySlug(link.server, link.slug)
        val account = accounts.current()?.takeIf { it.server == link.server }
            ?: return cached?.let { OpenResult.Opened(it.id, stale = true) } ?: OpenResult.NeedsAccount
        return when (val r = client.outing(account.server, account.token, link.slug)) {
            is ApiResult.Ok -> OpenResult.Opened(save(link.server, r.value, detail = true), stale = false)
            ApiResult.NotFound -> OpenResult.NotFound
            ApiResult.Unauthorized -> {
                onUnauthorized()
                OpenResult.NeedsAccount
            }
            else -> cached?.let { OpenResult.Opened(it.id, stale = true) } ?: OpenResult.Offline
        }
    }

    /** Fetches the outing again: through its personal link, else through the paired account. */
    suspend fun refresh(id: Long): RefreshResult {
        val row = dao.get(id) ?: return RefreshResult.NOT_FOUND
        val account = accounts.current()?.takeIf { it.server == row.server }
        val r = when {
            row.inviteToken != null -> client.guestOuting(row.server, row.inviteToken)
            account != null -> client.outing(account.server, account.token, row.remoteId)
            else -> return RefreshResult.NO_ACCESS
        }
        return when (r) {
            is ApiResult.Ok -> {
                save(row.server, r.value, detail = true, inviteToken = row.inviteToken)
                RefreshResult.UPDATED
            }
            ApiResult.NotFound -> RefreshResult.NOT_FOUND
            ApiResult.Unauthorized -> {
                if (row.inviteToken == null) onUnauthorized()
                RefreshResult.UNAUTHORIZED
            }
            else -> RefreshResult.OFFLINE
        }
    }

    /** Refreshes the paired account's outings ("My outings"). */
    suspend fun syncAccount(now: Long = System.currentTimeMillis()): RefreshResult {
        val account = accounts.current() ?: return RefreshResult.NO_ACCESS
        return when (val r = client.outings(account.server, account.token)) {
            is ApiResult.Ok -> {
                val listed = r.value.map { save(account.server, it, detail = false, fromAccount = true) }.toSet()
                // Still recent but no longer listed: the outing was deleted, or I was removed from it.
                dao.fromAccount()
                    .filter { it.id !in listed && it.server == account.server && it.startsAt > now - RECENT_MS }
                    .forEach { forgetFromAccount(it) }
                accounts.markSynced(now)
                RefreshResult.UPDATED
            }
            ApiResult.Unauthorized -> {
                onUnauthorized()
                RefreshResult.UNAUTHORIZED
            }
            else -> RefreshResult.OFFLINE
        }
    }

    /** Pairs the app with an account on [server] with a code from the QR or typed by hand. */
    suspend fun pair(server: String, code: String, deviceName: String): ApiResult<Unit> {
        val normalized = code.uppercase().filter { it.isLetterOrDigit() }
        return when (val r = client.pair(server, normalized, deviceName)) {
            is ApiResult.Ok -> {
                accounts.save(server, r.value)
                _accountRevoked.value = false
                ApiResult.Ok(Unit)
            }
            ApiResult.NotFound -> ApiResult.NotFound
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            ApiResult.TooManyRequests -> ApiResult.TooManyRequests
            is ApiResult.Failed -> r
            is ApiResult.Offline -> r
        }
    }

    /** Reads one of the account's own outings (a solo outing just made, #1766) and keeps it: its local id. */
    suspend fun fetchOwn(server: String, token: String, slug: String): ApiResult<Long> =
        when (val r = client.outing(server, token, slug)) {
            is ApiResult.Ok -> ApiResult.Ok(save(server, r.value, detail = true, fromAccount = true))
            else -> r.cast()
        }

    /** "Unpair": forgets the token and the outings only the account could read. */
    suspend fun unpair() {
        accounts.clear()
        dao.fromAccount().forEach { forgetFromAccount(it) }
    }

    /**
     * The imported track holding the outing's route, created on first use, so that following it reuses
     * everything a followed GPX has (map layer, profile, position on the track). Null without a route.
     */
    suspend fun routeTrack(id: Long): Long? = writeLock.withLock {
        val row = dao.get(id) ?: return null
        row.trackId?.let { existing -> if (tracks.get(existing) != null) return existing }
        val outing = row.decode() ?: return null
        val segments = outing.track?.segments().orEmpty()
        if (segments.isEmpty()) return null
        val meeting = outing.meeting?.takeIf { it.lat != null && it.lon != null }
        val trackId = tracks.insertAll(
            ImportedTrackEntity(
                name = outing.title.ifBlank { outing.track?.name.orEmpty() },
                importedAt = System.currentTimeMillis(),
                distanceM = TrackShape.distanceM(segments),
                elevationGainM = TrackShape.elevationGainM(segments),
                pointCount = segments.sumOf { it.size },
                outingId = row.id,
            ),
            points = { tid ->
                segments.flatMapIndexed { index, seg ->
                    seg.map { ImportedPointEntity(trackId = tid, segment = index, lat = it.lat, lon = it.lon, alt = it.alt) }
                }
            },
            waypoints = { tid ->
                listOfNotNull(meeting?.let { ImportedWaypointEntity(trackId = tid, name = it.label, lat = it.lat!!, lon = it.lon!!, alt = null) })
            },
        )
        dao.update(row.copy(trackId = trackId))
        trackId
    }

    /** Removes the outing from the app (not from singletrack). */
    suspend fun delete(id: Long) = writeLock.withLock {
        val row = dao.get(id) ?: return
        dropRoute(row.trackId)
        dao.delete(id)
    }

    private suspend fun forgetFromAccount(row: OutingEntity) = writeLock.withLock {
        if (row.inviteToken != null) {
            dao.update(row.copy(fromAccount = false))
        } else {
            dropRoute(row.trackId)
            dao.delete(row.id)
        }
    }

    /** singletrack refused the app token (401): it was revoked there, the app is no longer paired. */
    suspend fun onUnauthorized() {
        unpair()
        _accountRevoked.value = true
    }

    private suspend fun dropRoute(trackId: Long?) {
        if (trackId != null && trackId != followedTrackId()) tracks.delete(trackId)
    }

    /**
     * Stores what the server sent. A list entry only updates the list fields of an outing already
     * known in detail; a new detail whose route changed drops the old route track.
     */
    private suspend fun save(
        server: String,
        outing: Outing,
        detail: Boolean,
        inviteToken: String? = null,
        fromAccount: Boolean = false,
    ): Long = writeLock.withLock {
        val now = System.currentTimeMillis()
        val encoded = STORAGE.encodeToJsonElement(Outing.serializer(), outing).jsonObject
        val existing = dao.find(server, outing.id)
        if (existing == null) {
            return dao.insert(
                OutingEntity(
                    server = server,
                    remoteId = outing.id,
                    slug = outing.slug,
                    inviteToken = inviteToken,
                    fromAccount = fromAccount,
                    title = outing.title,
                    startsAt = outing.startsAtMs,
                    json = encoded.toString(),
                    hasDetail = detail,
                    fetchedAt = now,
                ),
            )
        }
        val json = if (detail || !existing.hasDetail) encoded else mergeSummary(existing.json, encoded)
        var trackId = existing.trackId
        if (detail && existing.hasDetail && existing.decode()?.track?.geometry != outing.track?.geometry) {
            dropRoute(trackId)
            trackId = null
        }
        dao.update(
            existing.copy(
                slug = outing.slug ?: existing.slug,
                inviteToken = inviteToken ?: existing.inviteToken,
                fromAccount = fromAccount || existing.fromAccount,
                title = outing.title,
                startsAt = outing.startsAtMs,
                json = json.toString(),
                hasDetail = detail || existing.hasDetail,
                fetchedAt = now,
                trackId = trackId,
            ),
        )
        existing.id
    }

    companion object {
        private const val RECENT_MS = 30L * 86_400_000

        /** Every field written, nulls included, so that a list entry can overwrite a stored detail's fields. */
        private val STORAGE = Json(AppApi.json) {
            encodeDefaults = true
            explicitNulls = true
        }

        /** The fields `GET /api/app/outings` sends; the rest of a detail is kept as it was. */
        private val SUMMARY_FIELDS = setOf(
            "id", "slug", "role", "title", "startsAt", "status", "cancelled", "done", "closed", "meeting",
            "distanceM", "elevationGainM", "level", "ebike", "organizerName",
        )

        internal fun mergeSummary(detailJson: String, summary: JsonObject): JsonObject {
            val detail = runCatching { AppApi.json.parseToJsonElement(detailJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            return JsonObject(detail + summary.filterKeys { it in SUMMARY_FIELDS })
        }
    }
}

/** The stored outing, or null if it cannot be read (should not happen: the app wrote it). */
fun OutingEntity.decode(): Outing? = runCatching { AppApi.json.decodeFromString(Outing.serializer(), json) }.getOrNull()
