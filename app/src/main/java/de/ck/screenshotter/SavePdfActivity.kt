package de.ck.screenshotter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Dialog zum Erstellen des PDFs: Dateiname, Speicherort und Layout wählen. */
class SavePdfActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var folderText: TextView
    private lateinit var nameField: EditText
    private lateinit var saveButton: Button

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "PDF speichern"
        val count = SessionStore(this).count()
        if (count == 0) {
            Toast.makeText(this, "Keine Aufnahmen vorhanden", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20f)
            setPadding(p, dp(8f), p, p)
        }
        col.addView(TextView(this).apply { text = "$count Aufnahme(n)"; textSize = 14f })

        col.addView(TextView(this).apply { text = "Dateiname"; textSize = 14f; setPadding(0, dp(12f), 0, 0) })
        nameField = EditText(this).apply {
            setText(savedInstanceState?.getString("name") ?: PdfBuilder.defaultName())
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            selectAll()
        }
        col.addView(nameField)
        col.addView(TextView(this).apply { text = ".pdf wird automatisch angehängt."; textSize = 12f })

        col.addView(TextView(this).apply { text = "Speicherort"; textSize = 14f; setPadding(0, dp(12f), 0, 0) })
        folderText = TextView(this).apply { textSize = 16f }
        col.addView(folderText)
        col.addView(LinearLayout(this).apply {
            addView(Button(this@SavePdfActivity).apply {
                text = "Ordner wählen …"; isAllCaps = false
                setOnClickListener { Folders.pick(this@SavePdfActivity) }
            })
            addView(Button(this@SavePdfActivity).apply {
                text = "Standard"; isAllCaps = false
                setOnClickListener { Folders.reset(this@SavePdfActivity); refreshFolder() }
            })
        })

        val cont = CheckBox(this).apply {
            text = "Fortlaufend auf A4-Seiten (sonst: eine Seite pro Aufnahme)"
            isChecked = prefs.continuousLayout
        }
        val overlap = CheckBox(this).apply {
            text = "Überlappungen beim Weiterscrollen entfernen"
            isChecked = prefs.removeOverlap
            isEnabled = prefs.continuousLayout
            setOnCheckedChangeListener { _, c -> prefs.removeOverlap = c }
        }
        cont.setOnCheckedChangeListener { _, c -> prefs.continuousLayout = c; overlap.isEnabled = c }
        col.addView(cont)
        col.addView(overlap)

        val buttons = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(12f), 0, 0) }
        buttons.addView(Button(this).apply {
            text = "Abbrechen"; isAllCaps = false; setOnClickListener { finish() }
        })
        saveButton = Button(this).apply { text = "Speichern"; isAllCaps = false; setOnClickListener { save() } }
        buttons.addView(saveButton)
        col.addView(buttons)

        setContentView(col)
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        refreshFolder()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("name", nameField.text.toString())
    }

    private fun refreshFolder() {
        folderText.text = Folders.label(prefs.folder)
    }

    @Deprecated("Activity-Result-API wird bewusst nicht verwendet, um ohne AndroidX-Activity auszukommen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (Folders.onResult(this, requestCode, resultCode, data)) refreshFolder()
    }

    private fun save() {
        saveButton.isEnabled = false
        saveButton.text = "Wird erstellt …"
        PdfJob.run(this, nameField.text.toString()) { _, msg ->
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            sendBroadcast(Intent(CaptureService.ACTION_SESSION_CHANGED).setPackage(packageName))
            finish()
        }
    }
}
