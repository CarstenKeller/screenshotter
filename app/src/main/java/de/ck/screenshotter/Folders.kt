package de.ck.screenshotter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/** Auswahl und Anzeige des Zielordners über das Storage Access Framework. */
object Folders {
    const val REQ_PICK = 4711

    fun pick(activity: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        activity.startActivityForResult(i, REQ_PICK)
    }

    /** Übernimmt das Ergebnis der Ordnerwahl; true, wenn ein Ordner gespeichert wurde. */
    fun onResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK) return false
        val uri = data?.data ?: return false
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, flags) }
        // vorherigen Ordner freigeben
        val prefs = Prefs(activity)
        prefs.folder?.takeIf { it != uri }?.let { old ->
            runCatching { activity.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs.folder = uri
        return true
    }

    fun reset(activity: Activity) {
        val prefs = Prefs(activity)
        prefs.folder?.let { old ->
            runCatching {
                activity.contentResolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.folder = null
    }

    /** Lesbare Bezeichnung, z. B. "Documents/Noten" für "primary:Documents/Noten". */
    fun label(uri: Uri?): String {
        if (uri == null) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Download/Screenshotter (Standard)"
            else "App-Speicher (Standard, über „Teilen“ sichern)"
        }
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
        val path = id.substringAfter(':', id)
        val volume = id.substringBefore(':', "")
        return when {
            path.isEmpty() -> if (volume == "primary") "Interner Speicher" else volume
            volume == "primary" || volume.isEmpty() -> path
            else -> "$volume/$path"
        }
    }
}
