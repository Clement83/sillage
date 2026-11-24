package io.github.clement83.sillage.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportFormat(val extension: String, val mimeType: String) {
    GPX("gpx", "application/gpx+xml"),
    KML("kml", "application/vnd.google-earth.kml+xml"),
}

/** A generated export, ready to share or save. */
data class ExportFile(val name: String, val format: ExportFormat, val content: String)

object ExportFiles {

    /** Hands the file to the Android share sheet (Drive, mail, singletrack in a browser…). */
    suspend fun share(context: Context, file: ExportFile, chooserTitle: String) {
        val cached = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, file.name).apply { writeText(file.content) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", cached)
        val send = Intent(Intent.ACTION_SEND)
            .setType(file.format.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    /** Writes the file where the user chose to save it (Storage Access Framework, no permission). */
    suspend fun save(context: Context, file: ExportFile, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(file.content.toByteArray()) }
            ?: error("Cannot open $target")
    }
}
