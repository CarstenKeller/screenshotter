package de.ck.screenshotter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vollbild-Overlay, das den Aufnahmebereich zeigt.
 *
 * - Bearbeitungsmodus ([editing] = true): Fenster nimmt Touches an; der Rahmen kann verschoben,
 *   an Ecken/Kanten in der Größe geändert oder außerhalb neu aufgezogen werden.
 * - Durchreichmodus ([editing] = false): nur der Rahmen wird gezeichnet, und zwar *außerhalb* des
 *   Aufnahmebereichs. Das Fenster ist dann nicht berührbar, die App darunter bleibt bedienbar.
 *
 * Alle Rechtecke in dieser Klasse sind in View-Koordinaten. Die Umrechnung in
 * Bildschirmkoordinaten übernimmt [CaptureService] über getLocationOnScreen().
 */
class SelectionOverlayView(context: Context) : View(context) {

    var editing = false
        set(v) { field = v; invalidate() }

    /** Aufnahmebereich in View-Koordinaten. */
    val selection = RectF()

    /** Wird nach Abschluss einer Änderung aufgerufen. */
    var onSelectionChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val stroke = 3f * density
    private val handleRadius = 9f * density
    private val touchSlop = 28f * density
    private val minSize = 48f * density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.parseColor("#FF1E6FD9")
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 10f * density), 0f)
    }
    private val dimPaint = Paint().apply { color = Color.parseColor("#88000000") }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF1E6FD9") }
    private val flashPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.parseColor("#FF2EBD59") }
    private var flashUntil = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (selection.isEmpty) {
            selection.set(w * 0.1f, h * 0.2f, w * 0.9f, h * 0.7f)
        }
        clampSelection()
    }

    fun setSelection(r: Rect) {
        selection.set(r)
        clampSelection()
        invalidate()
    }

    /** Kurzes grünes Aufleuchten als Rückmeldung nach einer Aufnahme. */
    fun flash() {
        flashUntil = System.currentTimeMillis() + 350
        invalidate()
        postDelayed({ invalidate() }, 400)
    }

    override fun onDraw(canvas: Canvas) {
        val s = selection
        // Rahmen um die Breite des Strichs nach außen versetzen, damit er nicht in der Aufnahme landet.
        val o = stroke / 2f + 1f
        val outer = RectF(s.left - o, s.top - o, s.right + o, s.bottom + o)
        if (editing) {
            canvas.drawRect(0f, 0f, width.toFloat(), s.top, dimPaint)
            canvas.drawRect(0f, s.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, s.top, s.left, s.bottom, dimPaint)
            canvas.drawRect(s.right, s.top, width.toFloat(), s.bottom, dimPaint)
            canvas.drawRect(outer, framePaint)
            for (p in corners()) canvas.drawCircle(p.first, p.second, handleRadius, handlePaint)
        } else {
            val p = if (System.currentTimeMillis() < flashUntil) flashPaint else framePaint
            canvas.drawRect(outer, p)
            canvas.drawRect(outer, dashPaint)
        }
    }

    private fun corners() = listOf(
        selection.left to selection.top, selection.right to selection.top,
        selection.left to selection.bottom, selection.right to selection.bottom
    )

    // --- Touch-Bearbeitung -------------------------------------------------------------------

    private var dragLeft = false
    private var dragTop = false
    private var dragRight = false
    private var dragBottom = false
    private var moving = false
    private var creating = false
    private var downX = 0f
    private var downY = 0f
    private val startRect = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing) return false
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                startRect.set(selection)
                val s = selection
                val nearL = abs(x - s.left) < touchSlop
                val nearR = abs(x - s.right) < touchSlop
                val nearT = abs(y - s.top) < touchSlop
                val nearB = abs(y - s.bottom) < touchSlop
                val inX = x > s.left - touchSlop && x < s.right + touchSlop
                val inY = y > s.top - touchSlop && y < s.bottom + touchSlop
                dragLeft = nearL && inY
                dragRight = nearR && inY && !dragLeft
                dragTop = nearT && inX
                dragBottom = nearB && inX && !dragTop
                val onEdge = dragLeft || dragRight || dragTop || dragBottom
                moving = !onEdge && s.contains(x, y)
                creating = !onEdge && !moving
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                when {
                    moving -> {
                        selection.set(startRect)
                        selection.offset(dx, dy)
                        // innerhalb des Bildschirms halten, Größe bewahren
                        val ox = when {
                            selection.left < 0 -> -selection.left
                            selection.right > width -> width - selection.right
                            else -> 0f
                        }
                        val oy = when {
                            selection.top < 0 -> -selection.top
                            selection.bottom > height -> height - selection.bottom
                            else -> 0f
                        }
                        selection.offset(ox, oy)
                    }
                    creating -> {
                        selection.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                    }
                    else -> {
                        if (dragLeft) selection.left = min(startRect.left + dx, selection.right - minSize)
                        if (dragRight) selection.right = max(startRect.right + dx, selection.left + minSize)
                        if (dragTop) selection.top = min(startRect.top + dy, selection.bottom - minSize)
                        if (dragBottom) selection.bottom = max(startRect.bottom + dy, selection.top + minSize)
                    }
                }
                clampSelection()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (creating && (selection.width() < minSize || selection.height() < minSize)) {
                    selection.set(startRect) // zu klein aufgezogen -> verwerfen
                }
                moving = false; creating = false
                clampSelection()
                invalidate()
                onSelectionChanged?.invoke()
            }
        }
        return true
    }

    private fun clampSelection() {
        if (width == 0 || height == 0) return
        selection.left = selection.left.coerceIn(0f, width.toFloat())
        selection.right = selection.right.coerceIn(0f, width.toFloat())
        selection.top = selection.top.coerceIn(0f, height.toFloat())
        selection.bottom = selection.bottom.coerceIn(0f, height.toFloat())
    }
}
