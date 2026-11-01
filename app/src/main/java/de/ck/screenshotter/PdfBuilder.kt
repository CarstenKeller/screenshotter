package de.ck.screenshotter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fügt die Einzelaufnahmen einer Sitzung zu einem PDF zusammen. */
object PdfBuilder {

    data class Result(val file: File, val downloadsUri: Uri?, val pages: Int, val images: Int)

    // A4 in PostScript-Punkten (1/72 Zoll)
    private const val A4_W = 595
    private const val A4_H = 842
    private const val MARGIN = 24f
    private const val GAP = 6f

    fun build(context: Context, images: List<File>, continuous: Boolean, removeOverlap: Boolean): Result {
        require(images.isNotEmpty()) { "Keine Aufnahmen vorhanden" }
        val outDir = File(context.filesDir, "pdf").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() } // nur das zuletzt erzeugte PDF behalten
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(outDir, "Screenshotter_$stamp.pdf")

        val doc = PdfDocument()
        val pages = try {
            if (continuous) layoutContinuous(doc, images, removeOverlap) else layoutOnePerPage(doc, images)
        } catch (t: Throwable) {
            doc.close()
            throw t
        }
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) copyToDownloads(context, outFile) else null
        return Result(outFile, uri, pages, images.size)
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Jede Aufnahme auf eine eigene Seite; Seitenbreite = A4-Breite, Höhe passend zum Bild. */
    private fun layoutOnePerPage(doc: PdfDocument, images: List<File>): Int {
        images.forEachIndexed { i, f ->
            val bmp = decode(f)
            val scale = A4_W.toFloat() / bmp.width
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, h, i + 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, A4_W.toFloat(), h.toFloat()), paint)
            doc.finishPage(page)
            bmp.recycle()
        }
        return images.size
    }

    /**
     * Aufnahmen untereinander auf A4-Seiten. Ist [removeOverlap] gesetzt, wird bei gleich breiten
     * Folgeaufnahmen der Bereich abgeschnitten, der bereits am Ende der vorigen Aufnahme zu sehen war
     * (typisch beim Weiterscrollen).
     */
    private fun layoutContinuous(doc: PdfDocument, images: List<File>, removeOverlap: Boolean): Int {
        val contentW = A4_W - 2 * MARGIN
        val bottom = A4_H - MARGIN
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var cursorY = MARGIN

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNo).create()).also {
                it.canvas.drawColor(Color.WHITE)
            }
            cursorY = MARGIN
        }

        var prevSig: RowSignature? = null
        newPage()
        for (f in images) {
            val bmp = decode(f)
            val sig = if (removeOverlap) RowSignature.of(bmp) else null
            val skipTop = if (sig != null && prevSig != null) Overlap.find(prevSig, sig) else 0
            prevSig = sig

            if (cursorY > MARGIN && skipTop == 0) cursorY += GAP
            val scale = contentW / bmp.width
            var srcY = skipTop
            while (srcY < bmp.height) {
                val rowsFit = ((bottom - cursorY) / scale).toInt()
                if (rowsFit < 1) {
                    newPage(); continue
                }
                val rows = minOf(bmp.height - srcY, rowsFit)
                val dst = RectF(MARGIN, cursorY, MARGIN + contentW, cursorY + rows * scale)
                page!!.canvas.drawBitmap(bmp, Rect(0, srcY, bmp.width, srcY + rows), dst, paint)
                cursorY += rows * scale
                srcY += rows
            }
            bmp.recycle()
        }
        page?.let { doc.finishPage(it) }
        return pageNo
    }

    private fun decode(f: File): Bitmap =
        BitmapFactory.decodeFile(f.absolutePath) ?: error("Bild nicht lesbar: ${f.name}")

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyToDownloads(context: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Screenshotter")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }
}

/** Kompakte Zeilen-Fingerabdrücke eines Bildes, um Überlappungen zu erkennen. */
class RowSignature(val width: Int, val hashes: LongArray, val uniform: BooleanArray) {
    companion object {
        fun of(bmp: Bitmap): RowSignature {
            val w = bmp.width
            // rechten Rand ignorieren: dort blenden viele Apps beim Scrollen einen Scrollbalken ein
            val usableW = (w * 0.94f).toInt().coerceAtLeast(1)
            val row = IntArray(w)
            val hashes = LongArray(bmp.height)
            val uniform = BooleanArray(bmp.height)
            for (y in 0 until bmp.height) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
                val first = row[0]
                var same = true
                for (x in 0 until usableW) {
                    val p = row[x]
                    if (p != first) same = false
                    h = (h xor p.toLong()) * 0x100000001b3L
                }
                hashes[y] = h
                uniform[y] = same
            }
            return RowSignature(w, hashes, uniform)
        }
    }
}

object Overlap {
    /**
     * Liefert die Anzahl Zeilen am oberen Rand von [next], die bereits am unteren Rand von [prev]
     * zu sehen waren (0 = keine Überlappung erkannt). Heuristik: größte Überlappung, bei der
     * mindestens 90 % der Zeilen identisch sind und genügend Zeilen echten Inhalt haben
     * (einfarbige Flächen allein gelten nicht als Beleg).
     */
    fun find(prev: RowSignature, next: RowSignature): Int {
        if (prev.width != next.width) return 0
        val h1 = prev.hashes.size
        val h2 = next.hashes.size
        val maxK = minOf(h1, h2)
        val minK = maxOf(12, maxK / 30)
        for (k in maxK downTo minK) {
            val allowed = k / 10
            var mismatches = 0
            var informative = 0
            var ok = true
            val off = h1 - k
            for (i in 0 until k) {
                if (prev.hashes[off + i] != next.hashes[i]) {
                    if (++mismatches > allowed) { ok = false; break }
                } else if (!next.uniform[i]) {
                    informative++
                }
            }
            if (ok && informative >= maxOf(8, k / 5)) return k
        }
        return 0
    }
}
