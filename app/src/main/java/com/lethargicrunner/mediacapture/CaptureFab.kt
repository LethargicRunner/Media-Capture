package com.lethargicrunner.mediacapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import kotlin.math.hypot
import kotlin.math.min

/**
 * Bouton flottant déplaçable : un tap ouvre le panneau, un glisser le déplace
 * et il se colle ensuite au bord gauche ou droit de l'écran.
 */
class CaptureFab(context: Context) : View(context) {

    var recording: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var badgeCount: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onTap: (() -> Unit)? = null
    var onMoved: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BADGE
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 10f * density
    }

    private var gradient: LinearGradient? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        elevation = 8f * density
        contentDescription = "Media Capture"
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = (4f * density).toInt()
                outline.setOval(
                    inset,
                    inset,
                    view.width - inset,
                    view.height - inset
                )
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(COLOR_START, COLOR_END),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 5f * density

        if (recording) {
            val phase = (SystemClock.uptimeMillis() % 1400L) / 1400f
            ringPaint.color = Color.argb(
                ((1f - phase) * 170).toInt(),
                225,
                29,
                72
            )
            canvas.drawCircle(cx, cy, radius + phase * 3.5f * density, ringPaint)

            circlePaint.shader = null
            circlePaint.color = COLOR_RECORD
            postInvalidateOnAnimation()
        } else {
            circlePaint.shader = gradient
        }
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // Onde sonore : 5 barres, celle du centre en corail quand on est au repos.
        val halfHeights = floatArrayOf(0.20f, 0.42f, 0.62f, 0.36f, 0.17f)
        val spacing = radius * 0.36f
        barPaint.strokeWidth = radius * 0.16f

        for (i in halfHeights.indices) {
            val x = cx + (i - 2) * spacing
            val half = halfHeights[i] * radius
            barPaint.color = if (i == 2 && !recording) COLOR_CORAL else Color.WHITE
            canvas.drawLine(x, cy - half, x, cy + half, barPaint)
        }

        if (badgeCount > 0) {
            val badgeRadius = 8.5f * density
            val bx = width - badgeRadius - 1f * density
            val by = badgeRadius + 1f * density
            canvas.drawCircle(bx, by, badgeRadius, badgePaint)

            val label = if (badgeCount > 99) "99+" else badgeCount.toString()
            badgeTextPaint.textSize = (if (label.length > 2) 8f else 10f) * density
            val textY = by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(label, bx, textY, badgeTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                }

                if (dragging) {
                    val container = parent as? View
                    val maxX = ((container?.width ?: 0) - width).coerceAtLeast(0)
                    val maxY = ((container?.height ?: 0) - height).coerceAtLeast(0)
                    x = (startX + dx).coerceIn(0f, maxX.toFloat())
                    y = (startY + dy).coerceIn(0f, maxY.toFloat())
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    snapToEdge()
                } else {
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) snapToEdge()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    private fun snapToEdge() {
        val containerWidth = (parent as? View)?.width ?: return
        val margin = EDGE_MARGIN_DP * density
        val target = if (x + width / 2f < containerWidth / 2f) {
            margin
        } else {
            containerWidth - width - margin
        }

        animate()
            .x(target)
            .setDuration(160)
            .withEndAction { onMoved?.invoke() }
            .start()
    }

    companion object {
        const val EDGE_MARGIN_DP = 8

        private val COLOR_START = Color.parseColor("#7C3AED")
        private val COLOR_END = Color.parseColor("#0EA5E9")
        private val COLOR_RECORD = Color.parseColor("#E11D48")
        private val COLOR_CORAL = Color.parseColor("#FF4D6D")
        private val COLOR_BADGE = Color.parseColor("#F97316")
    }
}
