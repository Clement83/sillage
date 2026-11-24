package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.settings.ServerUrl
import java.net.URI
import java.net.URLDecoder

/** What a scanned QR code or an opened link points to on a singletrack server. */
sealed interface SingletrackLink {
    val server: String

    /** `/s/<slug>`: a ride page (public or group link). */
    data class Outing(override val server: String, val slug: String) : SingletrackLink

    /** `/i/<token>`: a personal invitation link, readable without an account. */
    data class Invite(override val server: String, val token: String) : SingletrackLink

    /**
     * `/s/<openToken>/join`: the group link of an outing (its QR on the organiser's page, #1735). Joining
     * (typing a first name) happens on the website, which then gives the personal `/i/` link.
     */
    data class Join(override val server: String, val openToken: String) : SingletrackLink

    /** `singletrack://pair?server=…&code=…`: the "Pair the app" QR of the user menu (#1720). */
    data class Pair(override val server: String, val code: String) : SingletrackLink

    companion object {
        private val LOCALES = setOf("fr", "en")

        private val URL_IN_TEXT = Regex("(https?|singletrack)://[^\\s<>\"]+")

        /** The first singletrack link in a shared text ("Share" from the browser or a message). */
        fun find(text: String): SingletrackLink? =
            URL_IN_TEXT.findAll(text).firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ')', ';')) }

        fun parse(text: String): SingletrackLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            return when (uri.scheme?.lowercase()) {
                "singletrack" -> parsePair(uri)
                "http", "https" -> parseWeb(uri)
                else -> null
            }
        }

        private fun parsePair(uri: URI): Pair? {
            if (uri.host?.lowercase() != "pair" && uri.schemeSpecificPart?.startsWith("//pair") != true) return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val key = part.substringBefore('=', "")
                if (key.isEmpty()) null else key to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }.toMap()
            val server = params["server"]?.let(ServerUrl::normalize) ?: return null
            val code = params["code"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Pair(server, code)
        }

        private fun parseWeb(uri: URI): SingletrackLink? {
            val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            val index = segments.indexOfFirst { it == "s" || it == "i" }
            if (index < 0 || index + 1 >= segments.size) return null
            // Keep a sub-path install (/st/s/…) but drop a locale prefix (/fr/s/…).
            val base = segments.subList(0, index).let { if (it.lastOrNull() in LOCALES) it.dropLast(1) else it }
            val origin = "${uri.scheme}://${uri.rawAuthority}" + base.joinToString("") { "/$it" }
            val server = ServerUrl.normalize(origin) ?: return null
            val value = URLDecoder.decode(segments[index + 1], "UTF-8")
            return when {
                segments[index] == "i" -> Invite(server, value)
                segments.getOrNull(index + 2) == "join" -> Join(server, value)
                else -> Outing(server, value)
            }
        }
    }
}
