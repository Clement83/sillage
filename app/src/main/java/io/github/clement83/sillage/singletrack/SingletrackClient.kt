package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.offline.OfflineCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Outcome of a call to singletrack. Every failure is a value: the app never crashes on the network. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 404: unknown, revoked, or not visible to this account (singletrack never answers 403). */
    data object NotFound : ApiResult<Nothing>

    /** 401: app token missing, unknown or revoked; or a wrong, expired or reused pairing code. */
    data object Unauthorized : ApiResult<Nothing>

    /** 429: too many attempts, try again in a minute. */
    data object TooManyRequests : ApiResult<Nothing>

    /** Another HTTP error, with singletrack's code (`tooEarly`, `closed`, `tooLarge`, `notGpx`…) when it sent one. */
    data class Failed(val status: Int, val error: String?) : ApiResult<Nothing>

    /** No usable answer: offline, DNS, timeout, TLS, or a body that is not the expected JSON. */
    data class Offline(val cause: Throwable) : ApiResult<Nothing>
}

/** Where a ridden track goes: through the guest link, or through the paired account. */
sealed interface RideTarget {
    val server: String

    /** `POST <server>/api/i/<token>/ride`, no account (the personal link designates one invitation). */
    data class Guest(override val server: String, val inviteToken: String) : RideTarget

    /** `POST <server>/api/app/outings/<outingId>/ride` with the app token. */
    data class Account(override val server: String, val appToken: String, val outingId: String) : RideTarget
}

/**
 * singletrack's companion API (`/api/app/…`). Pure JVM: no Android type, so it is tested against a
 * local mock server. [server] is always a normalised base URL ([io.github.clement83.sillage.settings.ServerUrl]).
 */
class SingletrackClient(
    private val userAgent: String,
    private val http: OkHttpClient = defaultHttp(),
) {

    /** An outing through a guest's personal link (`/i/<token>`): no account needed. */
    suspend fun guestOuting(server: String, inviteToken: String): ApiResult<Outing> =
        get(url(server, "api/app/i", inviteToken), bearer = null, Outing.serializer())

    /** An outing of the paired account, by slug (organiser) or by id (organiser or linked guest). */
    suspend fun outing(server: String, appToken: String, ref: String): ApiResult<Outing> =
        get(url(server, "api/app/outings", ref), appToken, Outing.serializer())

    /** The paired account's outings: upcoming and the last 30 days, oldest first. */
    suspend fun outings(server: String, appToken: String): ApiResult<List<Outing>> =
        when (val r = get(url(server, "api/app/outings"), appToken, OutingList.serializer())) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.outings)
            else -> r.cast()
        }

    /** Exchanges a single-use pairing code (QR or typed) for a long-lived app token. */
    suspend fun pair(server: String, code: String, deviceName: String): ApiResult<String> {
        val url = url(server, "api/app/pair") ?: return badServer()
        val body = AppApi.json.encodeToString(PairRequest.serializer(), PairRequest(code, deviceName))
            .toRequestBody(JSON)
        return when (val r = call(request(url, null).post(body).build(), PairResponse.serializer())) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.token)
            else -> r.cast()
        }
    }

    /** Uploads (or replaces) my ridden track on the outing. singletrack keeps the GPX times. */
    suspend fun uploadRide(target: RideTarget, gpx: ByteArray, fileName: String): ApiResult<Unit> {
        val (url, bearer) = when (target) {
            is RideTarget.Guest -> url(target.server, "api/i", target.inviteToken, "ride") to null
            is RideTarget.Account -> url(target.server, "api/app/outings", target.outingId, "ride") to target.appToken
        }
        if (url == null) return badServer()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, gpx.toRequestBody(GPX))
            .build()
        return when (val r = call(request(url, bearer).post(body).build(), String.serializer(), expectJson = false)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            else -> r.cast()
        }
    }

    /** The regions the server offers for offline use (#1739). Account token only. */
    /**
     * Makes a ride a private solo outing of the account (#1765): it starts at the first point of
     * [gpx] and holds it as the ridden track. The same [clientId] again gives the same outing.
     * [startedAt] (ISO 8601 with offset) only counts when the GPX has no time.
     */
    suspend fun soloOuting(
        server: String,
        appToken: String,
        gpx: ByteArray,
        fileName: String,
        clientId: String,
        title: String,
        startedAt: String,
    ): ApiResult<SoloOutingResponse> {
        val url = url(server, "api/app/rides") ?: return badServer()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, gpx.toRequestBody(GPX))
            .addFormDataPart("clientId", clientId)
            .addFormDataPart("title", title)
            .addFormDataPart("startedAt", startedAt)
            .build()
        return call(request(url, appToken).post(body).build(), SoloOutingResponse.serializer())
    }

    suspend fun offlineCatalog(server: String, appToken: String): ApiResult<OfflineCatalog> =
        get(url(server, "api/app/offline"), appToken, OfflineCatalog.serializer())

    /** The offline MapLibre style of [regionId]: its layers are reused over the local tiles. */
    suspend fun offlineStyle(server: String, appToken: String, regionId: String): ApiResult<String> {
        val url = url(server, "api/app/offline", "style.json")?.newBuilder()?.addQueryParameter("region", regionId)?.build()
            ?: return badServer()
        return call(request(url, appToken).get().build(), String.serializer(), expectJson = false)
    }

    /**
     * Bytes [offset] to [offset] + [length] - 1 of an offline file (`<region>.pmtiles`), by HTTP range.
     * Fewer bytes only when the file ends sooner.
     */
    suspend fun offlineRange(server: String, appToken: String, file: String, offset: Long, length: Long): ApiResult<ByteArray> {
        val url = url(server, "api/app/offline", file) ?: return badServer()
        val request = request(url, appToken)
            .header("Accept", "*/*")
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .get().build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { r ->
                    when (r.code) {
                        206 -> {
                            val bytes = r.body?.bytes() ?: return@use ApiResult.Offline(IOException("No body"))
                            val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            val complete = bytes.size.toLong() == length ||
                                (bytes.size < length && total != null && offset + bytes.size == total)
                            if (complete) ApiResult.Ok(bytes) else ApiResult.Offline(IOException("Short range"))
                        }
                        401 -> ApiResult.Unauthorized
                        404 -> ApiResult.NotFound
                        429 -> ApiResult.TooManyRequests
                        else -> ApiResult.Failed(r.code, null)
                    }
                }
            } catch (e: IOException) {
                ApiResult.Offline(e)
            }
        }
    }

    /** A plain GET of public bytes (relief tiles), capped at [MAX_BODY_BYTES]. */
    suspend fun download(url: String): ApiResult<ByteArray> {
        val u = url.toHttpUrlOrNull() ?: return badServer()
        val request = Request.Builder().url(u).header("User-Agent", userAgent).get().build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { r ->
                    when {
                        r.isSuccessful -> {
                            val source = r.body?.source() ?: return@use ApiResult.Offline(IOException("No body"))
                            if (source.request(MAX_BODY_BYTES + 1)) ApiResult.Offline(IOException("Too large"))
                            else ApiResult.Ok(source.buffer.readByteArray())
                        }
                        r.code == 404 || r.code == 403 -> ApiResult.NotFound
                        r.code == 429 -> ApiResult.TooManyRequests
                        else -> ApiResult.Failed(r.code, null)
                    }
                }
            } catch (e: IOException) {
                ApiResult.Offline(e)
            }
        }
    }

    private suspend fun <T> get(url: HttpUrl?, bearer: String?, serializer: KSerializer<T>): ApiResult<T> =
        if (url == null) badServer() else call(request(url, bearer).get().build(), serializer)

    private fun request(url: HttpUrl, bearer: String?) = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
        .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>, expectJson: Boolean = true): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response -> read(response, serializer, expectJson) }
            } catch (e: IOException) {
                ApiResult.Offline(e)
            }
        }

    private fun <T> read(response: Response, serializer: KSerializer<T>, expectJson: Boolean): ApiResult<T> {
        // Read at most MAX_BODY_BYTES: request() is false once the whole (smaller) body is buffered.
        val source = response.body?.source() ?: return ApiResult.Offline(IOException("No body"))
        if (source.request(MAX_BODY_BYTES + 1)) {
            return ApiResult.Offline(IOException("Response larger than $MAX_BODY_BYTES bytes"))
        }
        val text = source.buffer.readUtf8()
        return when {
            response.isSuccessful -> if (!expectJson) {
                @Suppress("UNCHECKED_CAST")
                ApiResult.Ok(text as T)
            } else {
                runCatching { ApiResult.Ok(AppApi.json.decodeFromString(serializer, text)) }
                    .getOrElse { ApiResult.Offline(IOException("Unexpected JSON", it)) }
            }
            response.code == 401 -> ApiResult.Unauthorized
            response.code == 404 -> ApiResult.NotFound
            response.code == 429 -> ApiResult.TooManyRequests
            else -> ApiResult.Failed(
                response.code,
                runCatching { AppApi.json.decodeFromString(ApiError.serializer(), text).error }.getOrNull(),
            )
        }
    }

    private fun <T> badServer(): ApiResult<T> = ApiResult.Offline(IOException("Invalid server URL"))

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val GPX = "application/gpx+xml".toMediaType()

        /** An outing with its frozen route is a few hundred KB; anything far bigger is not singletrack. */
        const val MAX_BODY_BYTES = 8L * 1024 * 1024

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** `<server>/<prefix>/<segments…>`, each segment percent-encoded; null for a malformed server. */
        internal fun url(server: String, prefix: String, vararg segments: String): HttpUrl? =
            server.toHttpUrlOrNull()?.newBuilder()?.addPathSegments(prefix)?.apply {
                segments.forEach { addPathSegment(it) }
            }?.build()
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> ApiResult<*>.cast(): ApiResult<T> = when (this) {
    is ApiResult.Ok<*> -> error("Ok cannot be cast")
    else -> this as ApiResult<T>
}
