package io.github.clement83.sillage.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens [url] in the browser; false when nothing can. Sillage itself handles singletrack links
 * (`/s/…`, `/i/…`), so a plain VIEW intent could come straight back to the app: the browser
 * selector is tried first, a plain VIEW second.
 */
fun Context.openInBrowser(url: String): Boolean {
    val view = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val browser = Intent(view).apply { selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER) }
    return listOf(browser, view).any { intent ->
        try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

/** Opens a map app at the point (meeting point directions); false when none is installed. */
fun Context.openGeo(lat: Double, lon: Double, label: String?): Boolean = try {
    val query = "$lat,$lon" + (label?.let { "(" + android.net.Uri.encode(it) + ")" } ?: "")
    startActivity(Intent(Intent.ACTION_VIEW, "geo:$lat,$lon?q=$query".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
}
