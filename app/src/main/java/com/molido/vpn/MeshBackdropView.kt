package com.molido.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Lightweight, adaptive mesh backdrop for the Molido home and secondary screens.
 *
 * It is decorative only: it never reports connection state, invents network
 * metrics, or performs network work. The live state remains in the real UI
 * components above it. Animation is paused automatically when detached.
 */
class MeshBackdropView(
    context: Context,
    private val palette: AppAppearance.Palette,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val nodes = arrayOf(
        0.08f to 0.18f,
        0.36f to 0.10f,
        0.72f to 0.16f,
        0.94f to 0.34f,
        0.80f to 0.53f,
        0.53f to 0.44f,
        0.20f to 0.55f,
        0.05f to 0.76f,
        0.38f to 0.84f,
        0.73f to 0.78f,
        0.96f to 0.91f,
    )

    private val edges = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5,
        5 to 6, 6 to 0, 6 to 7, 7 to 8, 8 to 9,
        9 to 10, 5 to 8, 1 to 5, 4 to 9,
    )

    // Reused draw buffers: avoid allocating a List and Pair objects on every frame.
    private val pointX = FloatArray(nodes.size)
    private val pointY = FloatArray(nodes.size)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Two restrained ambient glows create depth without reducing text contrast.
        drawGlow(canvas, w * 0.82f, h * 0.13f, w * 0.72f, palette.violet, 0.10f)
        drawGlow(canvas, w * 0.10f, h * 0.72f, w * 0.68f, palette.primary, 0.075f)

        nodes.forEachIndexed { index, (x, y) ->
            pointX[index] = x * w
            pointY[index] = y * h
        }
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.7f * density
        paint.color = withAlpha(palette.primary, if (isDark()) 0.16f else 0.10f)
        edges.forEachIndexed { index, (a, b) ->
            val pulse = 0.78f + 0.22f * kotlin.math.sin(phase * 6.28318f + index * 0.71f)
            paint.alpha = (255 * (if (isDark()) 0.13f else 0.09f) * pulse).toInt()
            canvas.drawLine(pointX[a], pointY[a], pointX[b], pointY[b], paint)
        }

        paint.style = Paint.Style.FILL
        nodes.forEachIndexed { index, _ ->
            val pulse = 0.72f + 0.28f * kotlin.math.sin(phase * 6.28318f + index * 0.83f)
            paint.color = withAlpha(if (index % 3 == 0) palette.violet else palette.primary,
                (if (isDark()) 0.28f else 0.18f) * pulse)
            val radius = (if (index % 3 == 0) 2.0f else 1.35f) * density
            canvas.drawCircle(pointX[index], pointY[index], radius, paint)
        }
        paint.alpha = 255
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, strength: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(withAlpha(color, strength), withAlpha(color, strength * 0.34f), withAlpha(color, 0f)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }

    private fun isDark(): Boolean = palette.canvas.let { color ->
        val r = color shr 16 and 0xff
        val g = color shr 8 and 0xff
        val b = color and 0xff
        (r * 299 + g * 587 + b * 114) / 1000 < 140
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00ffffff) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    private fun startAnimation() {
        if (animator?.isStarted == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 12_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) startAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startAnimation() else stopAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
