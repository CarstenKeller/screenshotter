package de.ck.screenshotter

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val REQ_PROJECTION = 1
        private const val REQ_NOTIF = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var store: SessionStore

    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var notifButton: Button
    private lateinit var startButton: Button
    private lateinit var sessionText: TextView
    private lateinit var sessionButtons: LinearLayout
    private lateinit var pdfText: TextView
    private lateinit var pdfButtons: LinearLayout
    private lateinit var overlapBox: CheckBox
    private lateinit var folderText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = SessionStore(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refresh()
    }

    // --- UI ----------------------------------------------------------------------------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16f)
            setPadding(p, p, p, p)
        }
        fun heading(t: String) = TextView(this).apply {
            text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18f), 0, dp(6f))
        }
        fun body(t: String = "") = TextView(this).apply { text = t; textSize = 15f }
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        col.addView(body(
            "Wähle einen Bildschirmbereich und nimm ihn beliebig oft auf, während du die App " +
                "darunter normal weiter bedienst (z. B. scrollen). Am Ende werden alle Aufnahmen " +
                "zu einem PDF zusammengefügt."
        ))

        col.addView(heading("1. Berechtigungen"))
        overlayStatus = body()
        col.addView(overlayStatus)
        overlayButton = button("Über anderen Apps einblenden erlauben") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        col.addView(overlayButton)
        notifButton = button("Benachrichtigungen erlauben (optional)") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        col.addView(notifButton)

        col.addView(heading("2. PDF-Optionen"))
        val contBox = CheckBox(this).apply {
            text = "Aufnahmen fortlaufend auf A4-Seiten anordnen (sonst: eine Seite pro Aufnahme)"
            isChecked = prefs.continuousLayout
        }
        overlapBox = CheckBox(this).apply {
            text = "Überlappungen beim Weiterscrollen automatisch entfernen (experimentell)"
            isChecked = prefs.removeOverlap
            isEnabled = prefs.continuousLayout
            setOnCheckedChangeListener { _, c -> prefs.removeOverlap = c }
        }
        contBox.setOnCheckedChangeListener { _, c ->
            prefs.continuousLayout = c
            overlapBox.isEnabled = c
        }
        col.addView(contBox)
        col.addView(overlapBox)

        col.addView(body("Speicherort:"))
        folderText = body().apply { setTypeface(typeface, Typeface.BOLD) }
        col.addView(folderText)
        col.addView(row().apply {
            addView(button("Ordner wählen …") { Folders.pick(this@MainActivity) })
            addView(button("Standard") { Folders.reset(this@MainActivity); refresh() })
        })
        col.addView(body("Den Dateinamen legst du beim Erstellen des PDFs fest."))

        col.addView(heading("3. Aufnehmen"))
        col.addView(body(
            "Steuerleiste: 📷 aufnehmen · ↶ letzte löschen · ▭ Bereich ändern (✓ übernehmen) · " +
                "PDF erstellen · ✕ beenden. Am Griff ⠿ lässt sich die Leiste verschieben.\n" +
                "Hinweis: Inhalte, die eine App als geschützt markiert (z. B. Banking, DRM-Videos), " +
                "erscheinen in Aufnahmen schwarz."
        ))
        startButton = button("Aufnahme starten") { toggleCapture() }
        col.addView(startButton)

        col.addView(heading("Aktuelle Sitzung"))
        sessionText = body()
        col.addView(sessionText)
        sessionButtons = row().apply {
            addView(button("PDF erstellen") { createPdf() })
            addView(button("Verwerfen") { store.clear(); refresh() })
        }
        col.addView(sessionButtons)

        col.addView(heading("Letztes PDF"))
        pdfText = body()
        col.addView(pdfText)
        pdfButtons = row().apply {
            addView(button("Öffnen") { lastPdf()?.let { openPdf(it) } })
            addView(button("Teilen") { lastPdf()?.let { sharePdf(it) } })
        }
        col.addView(pdfButtons)

        val scroll = ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Ab Android 15 (targetSdk 35) zeichnet die App randlos – Systemleisten aussparen.
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        return scroll
    }

    private fun refresh() {
        folderText.text = Folders.label(prefs.folder)
        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatus.text = if (canOverlay) "✓ Einblenden über anderen Apps ist erlaubt."
        else "✗ Für den Auswahlrahmen muss „Über anderen Apps einblenden“ erlaubt werden."
        overlayButton.visibility = if (canOverlay) View.GONE else View.VISIBLE
        notifButton.visibility =
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) View.VISIBLE else View.GONE

        startButton.text = if (CaptureService.running) "Aufnahme beenden" else "Aufnahme starten"
        startButton.isEnabled = canOverlay || CaptureService.running

        val n = store.count()
        sessionText.text = if (n == 0) "Keine gespeicherten Aufnahmen." else "$n Aufnahme(n) gespeichert."
        sessionButtons.visibility = if (n == 0) View.GONE else View.VISIBLE

        val pdf = lastPdf()
        pdfText.text = when {
            pdf == null -> "Noch kein PDF erstellt."
            else -> pdf.name
        }
        pdfButtons.visibility = if (pdf == null) View.GONE else View.VISIBLE
    }

    // --- Aktionen ----------------------------------------------------------------------------

    private fun toggleCapture() {
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            startButton.postDelayed({ refresh() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            refresh(); return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Gesamten Bildschirm erzwingen; bei "nur eine App" würde der Wechsel in andere Apps nicht erfasst.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQ_PROJECTION)
    }

    @Deprecated("Activity-Result-API wird bewusst nicht verwendet, um ohne AndroidX-Activity auszukommen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (Folders.onResult(this, requestCode, resultCode, data)) {
            refresh(); return
        }
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Bildschirmaufnahme wurde nicht erlaubt", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
        startForegroundService(svc)
        // In den Hintergrund wechseln, damit die darunterliegende App sichtbar ist.
        moveTaskToBack(true)
    }

    private fun createPdf() {
        startActivity(Intent(this, SavePdfActivity::class.java))
    }

    private fun lastPdf(): File? =
        File(filesDir, "pdf").listFiles { f -> f.name.endsWith(".pdf") }?.maxByOrNull { it.lastModified() }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun openPdf(f: File) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(f), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Keine App zum Anzeigen von PDFs gefunden", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf(f: File) {
        val i = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uriFor(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "PDF teilen"))
    }
}
