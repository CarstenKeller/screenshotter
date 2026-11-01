package de.ck.screenshotter

import android.content.Context
import android.graphics.Rect

/** Persistente Einstellungen (Auswahlrahmen, PDF-Optionen). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Auswahlrahmen in Bildschirmkoordinaten (Pixel) oder null, wenn noch keiner gesetzt ist. */
    var selection: Rect?
        get() {
            if (!sp.contains("sel_l")) return null
            return Rect(sp.getInt("sel_l", 0), sp.getInt("sel_t", 0), sp.getInt("sel_r", 0), sp.getInt("sel_b", 0))
        }
        set(value) {
            val e = sp.edit()
            if (value == null) {
                e.remove("sel_l").remove("sel_t").remove("sel_r").remove("sel_b")
            } else {
                e.putInt("sel_l", value.left).putInt("sel_t", value.top)
                    .putInt("sel_r", value.right).putInt("sel_b", value.bottom)
            }
            e.apply()
        }

    /** true = Bilder fortlaufend auf A4-Seiten anordnen, false = eine Seite pro Aufnahme. */
    var continuousLayout: Boolean
        get() = sp.getBoolean("continuous", true)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    /** Überlappende Bereiche zwischen aufeinanderfolgenden Aufnahmen entfernen (nur fortlaufend). */
    var removeOverlap: Boolean
        get() = sp.getBoolean("overlap", true)
        set(v) = sp.edit().putBoolean("overlap", v).apply()
}
