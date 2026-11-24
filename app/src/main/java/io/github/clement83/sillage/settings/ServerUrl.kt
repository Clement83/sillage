package io.github.clement83.sillage.settings

import java.net.URI
import java.net.URISyntaxException

object ServerUrl {
    const val DEFAULT = "https://singletrack.quintard.me"

    /**
     * Returns the canonical form of a singletrack server address typed by the user, or null if it
     * is not a usable http(s) URL. A missing scheme means https; trailing slashes are dropped.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$host$port$path"
    }
}
