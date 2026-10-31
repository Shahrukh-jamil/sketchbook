package com.sketchbook.oss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

object UnifiedBrushRenderer {

    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val strokeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dstOutXfer = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val clearXfer = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val reusableOvalRect = RectF()
    private val reusableHsl = FloatArray(3)

    fun renderSingleDab(
        canvas: Canvas,
        brush: SketchBrushItem,
        x: Float,
        y: Float,
        tangentDeg: Float,
        pressureNorm: Float,
        baseColor: Int,
        isTransparentColor: Boolean,
        dabIndex: Int,
        deterministicRandom: Boolean,
        procHistory: MutableList<PointF>?,
        smudgeBitmap: Bitmap?,
        smudgeCarryColorHolder: IntArray?
    ) {
        val pClamped = pressureNorm.coerceIn(0.05f, 1.0f)
        val sizePressScale = (brush.sizeLightPressure + (brush.sizeHeavyPressure - brush.sizeLightPressure) * pClamped) / 100f
        val opacPressScale = (brush.opacityLightPressure + (brush.opacityHeavyPressure - brush.opacityLightPressure) * pClamped) / 100f

        var rad = (brush.size * 0.5f * sizePressScale.coerceIn(0.10f, 1.5f)).coerceAtLeast(1.0f)
        var dabAlpha = ((brush.opacity / 100f) * opacPressScale.coerceIn(0.05f, 1.0f) * 255f).roundToInt().coerceIn(2, 255)

        fun nextRand01(salt: Int): Float {
            return if (deterministicRandom) {
                val h = ((dabIndex * 73856093) xor (salt * 19349663)) and 0x7FFFFFFF
                (h % 10000) / 10000f
            } else {
                Random.nextFloat()
            }
        }

        var drawX = x
        var drawY = y

        if (brush.scatterJitter > 0.5f) {
            val maxScatter = rad * (brush.scatterJitter / 38f)
            val ang = nextRand01(1) * (Math.PI.toFloat() * 2f)
            val dist = (nextRand01(2) - 0.5f) * 2f * maxScatter
            drawX += cos(ang) * dist
            drawY += sin(ang) * dist
        }
        if (brush.sizeJitter > 0.5f) {
            val sFactor = 1f - nextRand01(3) * (brush.sizeJitter / 105f)
            rad = (rad * sFactor).coerceAtLeast(1.0f)
        }
        if (brush.opacityJitter > 0.5f) {
            val oFactor = 1f - nextRand01(4) * (brush.opacityJitter / 105f)
            dabAlpha = (dabAlpha * oFactor).roundToInt().coerceIn(2, 255)
        }

        val spacingNorm = (brush.spacing / 100f).coerceIn(0.02f, 1f)
        val flowScale = if (brush.brushType == SketchBrushType.MARKER) {
            0.32f
        } else if (brush.hardness < 75f) {
            (0.22f + spacingNorm * 0.65f).coerceIn(0.18f, 1f)
        } else {
            1f
        }
        val effectiveAlpha = (dabAlpha * flowScale).roundToInt().coerceIn(2, 255)

        val isEraseMode = (brush.brushType == SketchBrushType.ERASER || isTransparentColor)
        val activeXfer = if (isEraseMode) dstOutXfer else null

        val cr = if (isEraseMode) 0 else Color.red(baseColor)
        val cg = if (isEraseMode) 0 else Color.green(baseColor)
        val cb = if (isEraseMode) 0 else Color.blue(baseColor)

        when (brush.brushType) {
            SketchBrushType.SMUDGE -> {
                var sCol = baseColor
                if (smudgeBitmap != null && smudgeCarryColorHolder != null) {
                    val sxInt = drawX.toInt().coerceIn(0, smudgeBitmap.width - 1)
                    val syInt = drawY.toInt().coerceIn(0, smudgeBitmap.height - 1)
                    val sampled = smudgeBitmap.getPixel(sxInt, syInt)
                    val prev = smudgeCarryColorHolder[0]
                    if (Color.alpha(sampled) > 10) {
                        val mix = 0.30f
                        val mr = (Color.red(prev) * (1f - mix) + Color.red(sampled) * mix).toInt()
                        val mg = (Color.green(prev) * (1f - mix) + Color.green(sampled) * mix).toInt()
                        val mb = (Color.blue(prev) * (1f - mix) + Color.blue(sampled) * mix).toInt()
                        smudgeCarryColorHolder[0] = Color.rgb(mr, mg, mb)
                    }
                    sCol = smudgeCarryColorHolder[0]
                }
                val sr = if (isEraseMode) 0 else Color.red(sCol)
                val sg = if (isEraseMode) 0 else Color.green(sCol)
                val sb = if (isEraseMode) 0 else Color.blue(sCol)

                dabPaint.xfermode = activeXfer
                val smAlpha = (dabAlpha * 0.55f).toInt().coerceIn(8, 190)
                dabPaint.color = Color.WHITE
                dabPaint.shader = RadialGradient(
                    drawX, drawY, rad.coerceAtLeast(2f),
                    intArrayOf(Color.argb(smAlpha, sr, sg, sb), Color.argb(smAlpha / 2, sr, sg, sb), Color.TRANSPARENT),
                    floatArrayOf(0f, (brush.hardness / 100f).coerceIn(0.15f, 0.85f), 1f),
                    Shader.TileMode.CLAMP
                )
                drawTransformedStampShape(canvas, drawX, drawY, rad, brush)
                dabPaint.shader = null
                dabPaint.xfermode = null
            }

            SketchBrushType.GLOW -> {
                dabPaint.xfermode = activeXfer
                val outerR = rad * 1.9f
                dabPaint.color = Color.WHITE
                dabPaint.shader = RadialGradient(
                    drawX, drawY, outerR.coerceAtLeast(2f),
                    intArrayOf(
                        Color.argb(dabAlpha, if (isEraseMode) 0 else 255, if (isEraseMode) 0 else 255, if (isEraseMode) 0 else 255),
                        Color.argb((dabAlpha * 0.45f).toInt().coerceIn(8, 160), cr, cg, cb),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.38f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(drawX, drawY, outerR, dabPaint)
                dabPaint.shader = null
                dabPaint.xfermode = null
            }

            else -> {
                dabPaint.xfermode = activeXfer
                val hardNorm = (brush.hardness / 100f).coerceIn(0.02f, 1.0f)

                if (hardNorm < 0.82f) {
                    val coreStop = (hardNorm * 0.75f).coerceIn(0.02f, 0.75f)
                    val midAlpha = (effectiveAlpha * (0.35f + hardNorm * 0.45f)).toInt().coerceIn(2, 240)
                    dabPaint.color = Color.WHITE
                    dabPaint.shader = RadialGradient(
                        0f, 0f, rad.coerceAtLeast(1.5f),
                        intArrayOf(
                            Color.argb(effectiveAlpha, cr, cg, cb),
                            Color.argb(midAlpha, cr, cg, cb),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, coreStop, 1f),
                        Shader.TileMode.CLAMP
                    )
                } else {
                    dabPaint.shader = null
                    dabPaint.color = Color.argb(effectiveAlpha, cr, cg, cb)
                }

                canvas.save()
                canvas.translate(drawX, drawY)
                canvas.rotate(brush.rotation)
                val ry = rad * (brush.roundness / 100f).coerceIn(0.12f, 1.0f)
                if (brush.roundness < 96f) {
                    canvas.scale(1f, (brush.roundness / 100f).coerceIn(0.12f, 1.0f))
                    canvas.drawCircle(0f, 0f, rad, dabPaint)
                } else {
                    canvas.drawCircle(0f, 0f, rad, dabPaint)
                }

                if (brush.grainStrength > 5f && rad >= 3f) {
                    val grainFactor = (brush.grainStrength / 100f).coerceIn(0.05f, 1f)
                    dabPaint.shader = null
                    dabPaint.xfermode = dstOutXfer
                    val carveAlpha = (grainFactor * 165f).toInt().coerceIn(10, 185)
                    dabPaint.color = Color.argb(carveAlpha, 0, 0, 0)
                    val specks = (4 + (grainFactor * 10f).toInt()).coerceIn(4, 16)
                    for (g in 0 until specks) {
                        val ga = nextRand01(100 + g) * (Math.PI.toFloat() * 2f)
                        val gd = nextRand01(200 + g) * rad * 0.82f
                        val gx = cos(ga) * gd
                        val gy = sin(ga) * gd * (ry / rad)
                        val gr = (rad * (0.10f + 0.12f * nextRand01(300 + g))).coerceAtLeast(0.8f)
                        canvas.drawCircle(gx, gy, gr, dabPaint)
                    }
                }
                canvas.restore()
                dabPaint.shader = null
                dabPaint.xfermode = null
            }
        }
    }

    private fun drawTransformedStampShape(canvas: Canvas, x: Float, y: Float, rad: Float, brush: SketchBrushItem) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(brush.rotation)
        val ry = rad * (brush.roundness / 100f).coerceIn(0.12f, 1f)
        reusableOvalRect.set(-rad, -ry, rad, ry)
        canvas.drawOval(reusableOvalRect, dabPaint)
        canvas.restore()
    }

}
