package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.data.OutingEntity

/**
 * The outing's page on the website ("See on the website", #1727): answering, discussion and photos
 * happen there. Null when the app knows no page this user can open.
 */
fun OutingEntity.webUrl(outing: Outing? = decode()): String? = when {
    inviteToken != null -> "$server/i/$inviteToken"
    slug != null -> "$server/s/$slug"
    outing?.webPath != null -> server + outing.webPath
    else -> null
}

/** The web page a scanned or tapped link points to. */
fun SingletrackLink.webUrl(): String? = when (this) {
    is SingletrackLink.Outing -> "$server/s/$slug"
    is SingletrackLink.Invite -> "$server/i/$token"
    is SingletrackLink.Join -> "$server/s/$openToken/join"
    is SingletrackLink.Pair -> null
}
