package de.ck.screenshotter

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Vordergrunddienst, der den Bildschirm per MediaProjection spiegelt und zwei Overlay-Fenster
 * verwaltet: den Auswahlrahmen und eine verschiebbare Steuerleiste.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "de.ck.screenshotter.START"
        const val ACTION_STOP = "de.ck.screenshotter.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val CAPTURE_DELAY_MS = 300L

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var store: SessionStore
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private val imageLock = Any()
    private var latestImage: Image? = null
    private var screenW = 0
    private var screenH = 0
    private var densityDpi = 0

    private var overlay: SelectionOverlayView? = null
    private lateinit var overlayParams: WindowManager.LayoutParams
    private var bar: LinearLayout? = null
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var counterView: TextView
    private lateinit var editButton: TextView

    private var busy = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = SessionStore(this)
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (projection != null) return START_NOT_STICKY // läuft bereits
                // Muss vor getMediaProjection() geschehen (Pflicht ab Android 14).
                startInForeground()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else intent.getParcelableExtra(EXTRA_DATA)
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = data?.let { runCatching { mpm.getMediaProjection(code, it) }.getOrNull() }
                if (p == null) {
                    Toast.makeText(this, "Bildschirmaufnahme nicht erlaubt", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                projection = p
                p.registerCallback(projectionCallback, main) // ab Android 14 vor createVirtualDisplay nötig
                try {
                    setupCapture(p)
                    showOverlays()
                } catch (t: Throwable) {
                    Log.e(TAG, "Start fehlgeschlagen", t)
                    Toast.makeText(this, "Start fehlgeschlagen: ${t.message}", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                running = true
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        overlay?.let { runCatching { wm.removeView(it) } }
        bar?.let { runCatching { wm.removeView(it) } }
        overlay = null; bar = null
        virtualDisplay?.release(); virtualDisplay = null
        projection?.let { it.unregisterCallback(projectionCallback); it.stop() }
        projection = null
        imageHandler?.post {
            synchronized(imageLock) { latestImage?.close(); latestImage = null }
            reader?.close(); reader = null
        }
        imageThread?.quitSafely()
        super.onDestroy()
    }

    // --- Vordergrund-Benachrichtigung --------------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Aufnahme aktiv", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = store.count()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Screenshotter aktiv")
            .setContentText("$n Aufnahme(n) in dieser Sitzung")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    // --- Bildschirmspiegelung ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun readDisplaySize() {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        densityDpi = dm.densityDpi
    }

    @SuppressLint("WrongConstant")
    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (e: IllegalStateException) { null }
            if (img != null) synchronized(imageLock) {
                latestImage?.close()
                latestImage = img
            }
        }, imageHandler)
        return r
    }

    private fun setupCapture(p: MediaProjection) {
        imageThread = HandlerThread("capture-images").also { it.start() }
        imageHandler = Handler(imageThread!!.looper)
        readDisplaySize()
        val r = newReader()
        reader = r
        virtualDisplay = p.createVirtualDisplay(
            "screenshotter", screenW, screenH, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, imageHandler
        ) ?: error("VirtualDisplay konnte nicht erstellt werden")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Drehung: Spiegelung an die neue Bildschirmgröße anpassen. Ab Android 14 darf
        // createVirtualDisplay() nur einmal aufgerufen werden, daher resize() + neue Surface.
        val vd = virtualDisplay ?: return
        val oldW = screenW
        val oldH = screenH
        readDisplaySize()
        if (oldW == screenW && oldH == screenH) return
        val h = imageHandler ?: return
        h.post {
            val nr = newReader()
            vd.resize(screenW, screenH, densityDpi)
            vd.surface = nr.surface
            synchronized(imageLock) { latestImage?.close(); latestImage = null }
            reader?.close()
            reader = nr
        }
        main.postDelayed({ overlay?.invalidate() }, 200)
    }

    // --- Overlays ----------------------------------------------------------------------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** Maximal erlaubte Deckkraft, damit Touches durch ein Overlay zur App darunter gelangen (Android 12+). */
    private fun passThroughAlpha(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        else 1f

    private fun showOverlays() {
        val ov = SelectionOverlayView(this)
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = passThroughAlpha()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        ov.onSelectionChanged = { prefs.selection = selectionOnScreen() }
        wm.addView(ov, overlayParams)
        overlay = ov

        // Gespeicherten Rahmen übernehmen, sobald die Lage des Fensters bekannt ist.
        val saved = prefs.selection
        ov.post {
            if (saved != null) {
                val loc = IntArray(2)
                ov.getLocationOnScreen(loc)
                ov.setSelection(Rect(saved).apply { offset(-loc[0], -loc[1]) })
            } else {
                setEditing(true) // erster Start: gleich Bereich wählen lassen
            }
        }

        val b = buildBar()
        barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f).toInt()
            y = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        }
        wm.addView(b, barParams)
        bar = b
        refreshCounter()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBar(): LinearLayout {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(4f).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(Color.parseColor("#E6202124"))
            }
            elevation = dp(6f)
        }

        fun button(label: String, desc: String, accent: Boolean = false, onClick: () -> Unit) =
            TextView(this).apply {
                text = label
                contentDescription = desc
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                minWidth = dp(44f).toInt()
                minHeight = dp(44f).toInt()
                val h = dp(10f).toInt()
                setPadding(h, 0, h, 0)
                if (accent) background = GradientDrawable().apply {
                    cornerRadius = dp(22f); setColor(Color.parseColor("#FF1E6FD9"))
                }
                setOnClickListener { onClick() }
            }

        // Ziehgriff zum Verschieben der Leiste
        val handle = TextView(this).apply {
            text = "⠿"
            contentDescription = "Leiste verschieben"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 20f
            gravity = Gravity.CENTER
            minWidth = dp(32f).toInt()
            minHeight = dp(44f).toInt()
        }
        var startX = 0; var startY = 0; var rawX = 0f; var rawY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = barParams.x; startY = barParams.y; rawX = e.rawX; rawY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    barParams.x = (startX + (e.rawX - rawX)).roundToInt()
                    barParams.y = (startY + (e.rawY - rawY)).roundToInt()
                    bar?.let { wm.updateViewLayout(it, barParams) }
                }
            }
            true
        }

        counterView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = dp(28f).toInt()
        }
        editButton = button("▭", "Bereich wählen") { setEditing(!(overlay?.editing ?: false)) }

        ll.addView(handle)
        ll.addView(button("📷", "Aufnehmen", accent = true) { capture() })
        ll.addView(counterView)
        ll.addView(button("↶", "Letzte Aufnahme löschen") { undo() })
        ll.addView(editButton)
        ll.addView(button("PDF", "PDF erstellen") { makePdf() })
        ll.addView(button("✕", "Beenden") { stopSelf() })
        return ll
    }

    private fun setEditing(on: Boolean) {
        val ov = overlay ?: return
        ov.editing = on
        if (on) {
            overlayParams.flags = overlayParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            overlayParams.alpha = 1f
            editButton.text = "✓"
            editButton.contentDescription = "Bereich übernehmen"
        } else {
            overlayParams.flags = overlayParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            overlayParams.alpha = passThroughAlpha()
            editButton.text = "▭"
            editButton.contentDescription = "Bereich wählen"
            prefs.selection = selectionOnScreen()
        }
        wm.updateViewLayout(ov, overlayParams)
    }

    private fun selectionOnScreen(): Rect {
        val ov = overlay ?: return Rect()
        val loc = IntArray(2)
        ov.getLocationOnScreen(loc)
        val s = ov.selection
        return Rect(
            s.left.roundToInt() + loc[0], s.top.roundToInt() + loc[1],
            s.right.roundToInt() + loc[0], s.bottom.roundToInt() + loc[1]
        )
    }

    private fun refreshCounter() {
        counterView.text = store.count().toString()
        updateNotification()
    }

    // --- Aktionen ----------------------------------------------------------------------------

    private fun capture() {
        if (busy) return
        val ov = overlay ?: return
        val b = bar ?: return
        if (ov.editing) setEditing(false)
        busy = true
        val rect = selectionOnScreen()
        // Overlays kurz ausblenden, damit sie sicher nicht im Bild landen.
        ov.visibility = View.INVISIBLE
        b.visibility = View.INVISIBLE
        main.postDelayed({
            imageHandler?.post {
                val result = runCatching {
                    val bmp = synchronized(imageLock) { latestImage?.let { crop(it, rect) } }
                        ?: error("Noch kein Bild verfügbar")
                    store.add(bmp)
                    bmp.recycle()
                }
                main.post {
                    overlay?.visibility = View.VISIBLE
                    bar?.visibility = View.VISIBLE
                    busy = false
                    result.onSuccess { overlay?.flash() }
                    result.onFailure {
                        Log.w(TAG, "Aufnahme fehlgeschlagen", it)
                        Toast.makeText(this, "Aufnahme fehlgeschlagen: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                    refreshCounter()
                }
            }
        }, CAPTURE_DELAY_MS)
    }

    /** Schneidet [rect] (Bildschirmkoordinaten) aus einem RGBA_8888-Bild aus. */
    private fun crop(img: Image, rect: Rect): Bitmap {
        val r = Rect(rect)
        if (!r.intersect(0, 0, img.width, img.height) || r.isEmpty) error("Bereich außerhalb des Bildschirms")
        val plane = img.planes[0]
        val src = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val rowBytes = r.width() * 4
        val dst = ByteBuffer.allocateDirect(rowBytes * r.height())
        val row = ByteArray(rowBytes)
        for (y in r.top until r.bottom) {
            val base = y * rs + r.left * ps
            if (ps == 4) {
                src.position(base)
                src.get(row, 0, rowBytes)
            } else {
                for (x in 0 until r.width()) {
                    src.position(base + x * ps)
                    src.get(row, x * 4, 4)
                }
            }
            dst.put(row)
        }
        dst.rewind()
        val bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(dst)
        return bmp
    }

    private fun undo() {
        if (store.removeLast()) {
            Toast.makeText(this, "Letzte Aufnahme gelöscht", Toast.LENGTH_SHORT).show()
        }
        refreshCounter()
    }

    private fun makePdf() {
        if (store.count() == 0) {
            Toast.makeText(this, "Noch keine Aufnahmen", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "PDF wird erstellt …", Toast.LENGTH_SHORT).show()
        PdfJob.run(this) { ok, msg ->
            refreshCounter()
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (ok) {
                // Die sichtbaren Overlays erlauben den Aktivitätsstart aus dem Hintergrund.
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
            }
        }
    }
}

/** Erstellt das PDF im Hintergrund und leert danach die Sitzung. */
object PdfJob {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, done: (ok: Boolean, message: String) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val store = SessionStore(app)
            val prefs = Prefs(app)
            val msg = try {
                val r = PdfBuilder.build(app, store.images(), prefs.continuousLayout, prefs.removeOverlap)
                store.clear()
                val where = if (r.downloadsUri != null) "Download/Screenshotter" else "App-Speicher (über „Teilen“ sichern)"
                true to "PDF mit ${r.pages} Seite(n) aus ${r.images} Aufnahme(n) gespeichert: $where"
            } catch (t: Throwable) {
                Log.e("PdfJob", "PDF fehlgeschlagen", t)
                false to "PDF fehlgeschlagen: ${t.message}"
            }
            main.post { done(msg.first, msg.second) }
        }
    }
}
