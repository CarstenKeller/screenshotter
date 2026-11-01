package de.ck.screenshotter

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Hält die Einzelaufnahmen der aktuellen Sitzung als PNG-Dateien im App-Speicher.
 * Die Dateien bleiben erhalten, bis daraus ein PDF erstellt oder die Sitzung verworfen wurde,
 * damit beim versehentlichen Schließen des Overlays nichts verloren geht.
 */
class SessionStore(context: Context) {
    private val dir = File(context.filesDir, "session").apply { mkdirs() }

    fun images(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList()

    fun count(): Int = images().size

    @Synchronized
    fun add(bitmap: Bitmap): File {
        val next = (images().lastOrNull()?.nameWithoutExtension?.toIntOrNull() ?: 0) + 1
        val file = File(dir, "%05d.png".format(next))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    @Synchronized
    fun removeLast(): Boolean = images().lastOrNull()?.delete() ?: false

    @Synchronized
    fun clear() {
        images().forEach { it.delete() }
    }
}
