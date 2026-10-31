package com.sketchbook.oss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object LayerStudioCompositor {

    private var checkerboardShader: BitmapShader? = null
    private var cachedTextureBmp: Bitmap? = null
    private var cachedTextureHash: Int = 0
    private val texShaderMatrix = Matrix()

    private var workCompositeBmp: Bitmap? = null
    private var clipMaskedTempBmp: Bitmap? = null

    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun getCheckerboardShader(): BitmapShader {
        val existing = checkerboardShader
        if (existing != null) return existing
        // Exact 16px square checkerboard (#D9D9D9 and #999999) from Screenshot_20261027_163753.jpg
        val sq = 16
        val tile = Bitmap.createBitmap(sq * 2, sq * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        val p = Paint()
        val cLight = Color.rgb(217, 217, 217)
        val cDark = Color.rgb(153, 153, 153)
        p.color = cLight
        c.drawRect(0f, 0f, sq * 2f, sq * 2f, p)
        p.color = cDark
        c.drawRect(0f, 0f, sq.toFloat(), sq.toFloat(), p)
        c.drawRect(sq.toFloat(), sq.toFloat(), sq * 2f, sq * 2f, p)
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
            checkerboardShader = it
        }
    }

    private fun getProceduralSurfaceShader(cfg: BackgroundProceduralConfig, baseCol: Int): BitmapShader? {
        if (cfg.surfacePreset == 0 || cfg.grainIntensity <= 1f) return null
        val hash = ((cfg.surfacePreset * 31 + cfg.grainIntensity.roundToInt()) * 31 + cfg.warmthTint.roundToInt()) * 31 + baseCol
        if (cachedTextureBmp == null || cachedTextureHash != hash) {
            val sz = 128
            val bmp = cachedTextureBmp ?: Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { cachedTextureBmp = it }
            val pixels = IntArray(sz * sz)
            val br = Color.red(baseCol)
            val bg = Color.green(baseCol)
            val bb = Color.blue(baseCol)
            val warmR = (cfg.warmthTint * 0.45f).roundToInt()
            val warmB = (-cfg.warmthTint * 0.45f).roundToInt()
            val amp = (cfg.grainIntensity * 0.65f).roundToInt()

            for (y in 0 until sz) {
                for (x in 0 until sz) {
                    val h = ((x * 73856093) xor (y * 19349663) xor (cfg.surfacePreset * 83492791)) and 0x7FFFFFFF
                    val noise01 = (h % 1000) / 1000f - 0.5f
                    val delta = when (cfg.surfacePreset) {
                        1 -> { // Cold Press Watercolor Paper
                            val wave = sin(x * 0.19f) * cos(y * 0.19f) * 0.4f + noise01 * 0.6f
                            (wave * amp * 1.4f).roundToInt()
                        }
                        2 -> { // Rough Woven Canvas
                            val warp = sin(x * 0.65f)
                            val weft = sin(y * 0.65f)
                            (((warp + weft) * 0.45f + noise01 * 0.25f) * amp).roundToInt()
                        }
                        3 -> { // Charcoal Laid Paper (Horizontal ribs + vertical chain lines)
                            val rib = if (y % 4 == 0) -0.45f else 0.18f
                            val chain = if (x % 24 == 0) -0.35f else 0f
                            ((rib + chain + noise01 * 0.45f) * amp).roundToInt()
                        }
                        4 -> { // Halftone Stipple Matrix
                            val dx = (x % 8) - 3.5f
                            val dy = (y % 8) - 3.5f
                            val d = hypot(dx, dy)
                            (if (d < 2.2f) -amp / 2 else amp / 4)
                        }
                        else -> { // Aged Parchment Fiber
                            val fiber = sin((x + y * 0.5f) * 0.15f) * 0.35f + noise01 * 0.65f
                            (fiber * amp * 1.3f).roundToInt()
                        }
                    }
                    val r = (br + warmR + delta).coerceIn(0, 255)
                    val g = (bg + (warmR / 3) + delta).coerceIn(0, 255)
                    val b = (bb + warmB + delta).coerceIn(0, 255)
                    pixels[y * sz + x] = Color.rgb(r, g, b)
                }
            }
            bmp.setPixels(pixels, 0, sz, 0, 0, sz, sz)
            cachedTextureHash = hash
        }
        val sh = BitmapShader(cachedTextureBmp!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val sc = (cfg.textureScale / 100f).coerceIn(0.25f, 2.5f)
        texShaderMatrix.reset()
        texShaderMatrix.setScale(sc, sc)
        sh.setLocalMatrix(texShaderMatrix)
        return sh
    }

    /**
     * Draws the live background (or transparency checkerboard when backgroundVisible == false
     * and forExport == false) onto targetCanvas of dimensions [w x h].
     */
    fun drawBackgroundToCanvas(
        targetCanvas: Canvas,
        project: SketchProjectState,
        w: Int,
        h: Int,
        forExport: Boolean
    ) {
        val wf = w.toFloat()
        val hf = h.toFloat()

        if (!project.backgroundVisible) {
            if (forExport) {
                targetCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            } else {
                fillPaint.shader = getCheckerboardShader()
                targetCanvas.drawRect(0f, 0f, wf, hf, fillPaint)
                fillPaint.shader = null
            }
            return
        }

        val cfg = project.backgroundConfig
        val baseCol = project.backgroundColor
        val warmR = (cfg.warmthTint * 0.45f).roundToInt()
        val warmB = (-cfg.warmthTint * 0.45f).roundToInt()
        val tintedBase = Color.rgb(
            (Color.red(baseCol) + warmR).coerceIn(0, 255),
            (Color.green(baseCol) + warmR / 3).coerceIn(0, 255),
            (Color.blue(baseCol) + warmB).coerceIn(0, 255)
        )

        val texShader = getProceduralSurfaceShader(cfg, baseCol)
        if (texShader != null) {
            fillPaint.shader = texShader
            targetCanvas.drawRect(0f, 0f, wf, hf, fillPaint)
            fillPaint.shader = null
        } else {
            fillPaint.color = tintedBase
            targetCanvas.drawRect(0f, 0f, wf, hf, fillPaint)
        }

        // Lighting / Vignette Pass
        if (cfg.lightingPreset != 0 && cfg.vignetteStrength > 1f) {
            val alpha = (cfg.vignetteStrength * 2.15f).roundToInt().coerceIn(0, 225)
            val softStop = (1f - (cfg.vignetteSoftness / 100f) * 0.75f).coerceIn(0.15f, 0.92f)
            val focusY = hf * (cfg.focusElevation / 100f).coerceIn(0.05f, 0.95f)
            val maxR = hypot(wf, hf) * 0.62f

            when (cfg.lightingPreset) {
                1 -> { // Radial Vignette (Darkened edges)
                    fillPaint.shader = RadialGradient(
                        wf * 0.5f, focusY, maxR,
                        intArrayOf(Color.TRANSPARENT, Color.argb(alpha / 3, 0, 0, 0), Color.argb(alpha, 0, 0, 0)),
                        floatArrayOf(0f, softStop, 1f),
                        Shader.TileMode.CLAMP
                    )
                    targetCanvas.drawRect(0f, 0f, wf, hf, fillPaint)
                    fillPaint.shader = null
                }
                2 -> { // Linear Horizon Gradient
                    fillPaint.shader = LinearGradient(
                        0f, 0f, 0f, hf,
                        intArrayOf(
                            Color.argb(alpha, 255, 255, 255),
                            Color.TRANSPARENT,
                            Color.argb(alpha, 0, 0, 0)
                        ),
                        floatArrayOf(0f, (cfg.focusElevation / 100f).coerceIn(0.1f, 0.9f), 1f),
                        Shader.TileMode.CLAMP
                    )
                    targetCanvas.drawRect(0f, 0f, wf, hf, fillPaint)
                    fillPaint.shader = null
                }
                3 -> { // Warm Center Spotlight
                    fillPaint.shader = RadialGradient(
                        wf * 0.5f, focusY, maxR * 0.85f,
                        intArrayOf(Color.argb(alpha, 255, 252, 235), Color.TRANSPARENT, Color.argb(alpha / 2, 0, 0, 0)),
                        floatArrayOf(0f, softStop, 1f),
                        Shader.TileMode.CLAMP
                    )
                    targetCanvas.drawRect(0f, 0f, wf, hf, fillPaint)
                    fillPaint.shader = null
                }
            }
        }

        // Precision Grids Pass
        if (cfg.gridPreset != 0 && cfg.gridOpacity > 1f) {
            val luma = (0.299f * Color.red(tintedBase) + 0.587f * Color.green(tintedBase) + 0.114f * Color.blue(tintedBase))
            val useLightLines = if (cfg.invertGridContrast) (luma >= 128f) else (luma < 128f)
            val baseGridAlpha = (cfg.gridOpacity * 2.25f).roundToInt().coerceIn(8, 235)
            val lineCol = if (useLightLines) Color.argb(baseGridAlpha, 240, 245, 255) else Color.argb(baseGridAlpha, 30, 35, 45)
            val subCol = if (useLightLines) Color.argb(baseGridAlpha / 2, 240, 245, 255) else Color.argb(baseGridAlpha / 2, 30, 35, 45)
            val spacing = cfg.gridSpacing.coerceIn(20f, 360f)
            val subdiv = cfg.gridSubdivisions.roundToInt().coerceIn(1, 8)

            when (cfg.gridPreset) {
                1 -> { // Square Graph Grid
                    val subStep = (spacing / subdiv).coerceAtLeast(6f)
                    if (subdiv > 1) {
                        gridPaint.color = subCol
                        gridPaint.strokeWidth = 1.2f
                        var x = 0f
                        while (x <= wf) { targetCanvas.drawLine(x, 0f, x, hf, gridPaint); x += subStep }
                        var y = 0f
                        while (y <= hf) { targetCanvas.drawLine(0f, y, wf, y, gridPaint); y += subStep }
                    }
                    gridPaint.color = lineCol
                    gridPaint.strokeWidth = 2.4f
                    var mx = 0f
                    while (mx <= wf) { targetCanvas.drawLine(mx, 0f, mx, hf, gridPaint); mx += spacing }
                    var my = 0f
                    while (my <= hf) { targetCanvas.drawLine(0f, my, wf, my, gridPaint); my += spacing }
                }
                2 -> { // Isometric 30°/60° Grid
                    gridPaint.color = lineCol
                    gridPaint.strokeWidth = 1.8f
                    var x = 0f
                    while (x <= wf) { targetCanvas.drawLine(x, 0f, x, hf, gridPaint); x += spacing }
                    val tan30 = 0.57735f
                    val dy = spacing * tan30 * 2f
                    val spanY = wf * tan30
                    var yStart = -spanY
                    while (yStart <= hf + spanY) {
                        targetCanvas.drawLine(0f, yStart, wf, yStart + spanY, gridPaint)
                        targetCanvas.drawLine(0f, yStart, wf, yStart - spanY, gridPaint)
                        yStart += dy
                    }
                }
                3 -> { // Perspective Horizon & Vanishing Rays
                    val vy = hf * (cfg.focusElevation / 100f).coerceIn(0.1f, 0.9f)
                    val vx = wf * 0.5f
                    gridPaint.color = lineCol
                    gridPaint.strokeWidth = 3f
                    targetCanvas.drawLine(0f, vy, wf, vy, gridPaint)
                    gridPaint.strokeWidth = 1.6f
                    val numRays = (subdiv * 6).coerceIn(8, 48)
                    for (i in 0 until numRays) {
                        val ang = (i.toDouble() / numRays) * Math.PI * 2.0
                        val rx = vx + cos(ang).toFloat() * hypot(wf, hf)
                        val ry = vy + sin(ang).toFloat() * hypot(wf, hf)
                        targetCanvas.drawLine(vx, vy, rx, ry, gridPaint)
                    }
                }
                4 -> { // Dot Matrix Grid
                    fillPaint.color = lineCol
                    val dotR = (2.2f + subdiv * 0.35f).coerceIn(2f, 6f)
                    var y = spacing * 0.5f
                    while (y < hf) {
                        var x = spacing * 0.5f
                        while (x < wf) {
                            targetCanvas.drawCircle(x, y, dotR, fillPaint)
                            x += spacing
                        }
                        y += spacing
                    }
                }
                5 -> { // Storyboard / Rule of Thirds + Golden Ratio + Ruled Lines
                    gridPaint.color = subCol
                    gridPaint.strokeWidth = 1.4f
                    var y = spacing
                    while (y < hf) {
                        targetCanvas.drawLine(0f, y, wf, y, gridPaint)
                        y += spacing
                    }
                    gridPaint.color = lineCol
                    gridPaint.strokeWidth = 3.2f
                    for (k in 1..2) {
                        val tx = wf * (k / 3f)
                        val ty = hf * (k / 3f)
                        targetCanvas.drawLine(tx, 0f, tx, hf, gridPaint)
                        targetCanvas.drawLine(0f, ty, wf, ty, gridPaint)
                    }
                }
            }
        }
    }

    /**
     * Composites all visible layers of [project] (plus Background or Checkerboard) into a single Bitmap.
     */
    fun renderProjectComposite(
        project: SketchProjectState,
        forExport: Boolean
    ): Bitmap {
        project.ensureLayersInitialized()
        val w = project.width.coerceIn(1, 8192)
        val h = project.height.coerceIn(1, 8192)

        var compBmp = workCompositeBmp
        if (compBmp == null || compBmp.isRecycled || compBmp.width != w || compBmp.height != h) {
            compBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            workCompositeBmp = compBmp
        }
        val c = Canvas(compBmp!!)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawBackgroundToCanvas(c, project, w, h, forExport = forExport)

        // Iterate from bottom-most layer (lastIndex) to top-most layer (0)
        var baseClippingLayerBmp: Bitmap? = null
        for (i in project.layers.lastIndex downTo 0) {
            val layer = project.layers[i]
            if (!layer.visible || layer.opacity <= 0.1f || layer.bitmap.isRecycled) {
                if (!layer.clippingMask) baseClippingLayerBmp = null
                continue
            }

            val srcBmp = if (layer.clippingMask && baseClippingLayerBmp != null && !baseClippingLayerBmp.isRecycled) {
                applyClippingMaskToTemp(layer.bitmap, baseClippingLayerBmp)
            } else {
                baseClippingLayerBmp = layer.bitmap
                layer.bitmap
            }

            compositeSingleLayer(c, compBmp, srcBmp, layer.blendMode, (layer.opacity / 100f).coerceIn(0f, 1f))
        }
        return compBmp
    }

    private fun applyClippingMaskToTemp(layerBmp: Bitmap, maskBmp: Bitmap): Bitmap {
        val w = layerBmp.width
        val h = layerBmp.height
        var tmp = clipMaskedTempBmp
        if (tmp == null || tmp.isRecycled || tmp.width != w || tmp.height != h) {
            tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            clipMaskedTempBmp = tmp
        }
        val tc = Canvas(tmp!!)
        tc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        tc.drawBitmap(layerBmp, 0f, 0f, null)
        layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        layerPaint.alpha = 255
        tc.drawBitmap(maskBmp, 0f, 0f, layerPaint)
        layerPaint.xfermode = null
        return tmp
    }

    /**
     * Composites [srcBmp] onto [dstBmp] / [dstCanvas] using any of the 20 SketchBook Blend Modes,
     * including genuine SketchBook Glow and Soft Glow!
     */
    fun compositeSingleLayer(
        dstCanvas: Canvas,
        dstBmp: Bitmap,
        srcBmp: Bitmap,
        mode: LayerBlendMode,
        opacityNorm: Float
    ) {
        val alphaInt = (opacityNorm * 255f).roundToInt().coerceIn(0, 255)
        if (alphaInt <= 0) return

        // Fast native Canvas PorterDuff / Android 10+ BlendMode path for standard modes
        val nativeHandled = when (mode) {
            LayerBlendMode.NORMAL -> {
                layerPaint.xfermode = null
                if (Build.VERSION.SDK_INT >= 29) layerPaint.blendMode = null
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                true
            }
            LayerBlendMode.MULTIPLY -> {
                layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                layerPaint.xfermode = null
                true
            }
            LayerBlendMode.SCREEN -> {
                layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                layerPaint.xfermode = null
                true
            }
            LayerBlendMode.DARKEN -> {
                layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                layerPaint.xfermode = null
                true
            }
            LayerBlendMode.LIGHTEN -> {
                layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                layerPaint.xfermode = null
                true
            }
            LayerBlendMode.LINEAR_DODGE -> {
                layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                layerPaint.xfermode = null
                true
            }
            LayerBlendMode.OVERLAY -> {
                layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                layerPaint.xfermode = null
                true
            }
            LayerBlendMode.ALPHA_MASK -> {
                layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                layerPaint.xfermode = null
                true
            }
            LayerBlendMode.INVERSE_ALPHA_MASK -> {
                layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                layerPaint.alpha = alphaInt
                dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                layerPaint.xfermode = null
                true
            }
            else -> {
                if (Build.VERSION.SDK_INT >= 29 &&
                    mode != LayerBlendMode.GLOW &&
                    mode != LayerBlendMode.SOFT_GLOW &&
                    mode != LayerBlendMode.LINEAR_BURN
                ) {
                    val bm = when (mode) {
                        LayerBlendMode.COLOR_BURN -> BlendMode.COLOR_BURN
                        LayerBlendMode.COLOR_DODGE -> BlendMode.COLOR_DODGE
                        LayerBlendMode.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
                        LayerBlendMode.HARD_LIGHT -> BlendMode.HARD_LIGHT
                        LayerBlendMode.HUE -> BlendMode.HUE
                        LayerBlendMode.SATURATION -> BlendMode.SATURATION
                        LayerBlendMode.COLOR -> BlendMode.COLOR
                        LayerBlendMode.LUMINOSITY -> BlendMode.LUMINOSITY
                        else -> null
                    }
                    if (bm != null) {
                        layerPaint.xfermode = null
                        layerPaint.blendMode = bm
                        layerPaint.alpha = alphaInt
                        dstCanvas.drawBitmap(srcBmp, 0f, 0f, layerPaint)
                        layerPaint.blendMode = null
                        true
                    } else false
                } else false
            }
        }

        if (nativeHandled) return

        // Custom per-pixel shader pass for SketchBook GLOW, SOFT_GLOW, LINEAR_BURN (and API < 29 fallbacks)
        val w = min(dstBmp.width, srcBmp.width)
        val h = min(dstBmp.height, srcBmp.height)
        val chunkRows = min(64, h).coerceAtLeast(1)
        val dstPixels = IntArray(w * chunkRows)
        val srcPixels = IntArray(w * chunkRows)
        val hslB = FloatArray(3)
        val hslS = FloatArray(3)

        var y = 0
        while (y < h) {
            val rows = min(chunkRows, h - y)
            val count = w * rows
            srcBmp.getPixels(srcPixels, 0, w, 0, y, w, rows)

            // Quick skip if chunk is completely transparent in srcBmp
            var hasNonZero = false
            for (i in 0 until count) {
                if ((srcPixels[i] ushr 24) > 0) {
                    hasNonZero = true
                    break
                }
            }
            if (!hasNonZero) {
                y += rows
                continue
            }

            dstBmp.getPixels(dstPixels, 0, w, 0, y, w, rows)
            for (i in 0 until count) {
                val sc = srcPixels[i]
                val saRaw = sc ushr 24
                if (saRaw == 0) continue

                val sa = ((saRaw * alphaInt) / 255) / 255f
                if (sa <= 0.001f) continue

                val dc = dstPixels[i]
                val da = (dc ushr 24) / 255f
                val dr = ((dc shr 16) and 0xFF) / 255f
                val dg = ((dc shr 8) and 0xFF) / 255f
                val db = (dc and 0xFF) / 255f

                val sr = ((sc shr 16) and 0xFF) / 255f
                val sg = ((sc shr 8) and 0xFF) / 255f
                val sb = (sc and 0xFF) / 255f

                var outR: Float
                var outG: Float
                var outB: Float

                when (mode) {
                    LayerBlendMode.LINEAR_BURN -> {
                        outR = (dr + sr - 1f).coerceIn(0f, 1f)
                        outG = (dg + sg - 1f).coerceIn(0f, 1f)
                        outB = (db + sb - 1f).coerceIn(0f, 1f)
                    }
                    LayerBlendMode.COLOR_BURN -> {
                        outR = if (sr <= 0.001f) 0f else (1f - (1f - dr) / sr).coerceIn(0f, 1f)
                        outG = if (sg <= 0.001f) 0f else (1f - (1f - dg) / sg).coerceIn(0f, 1f)
                        outB = if (sb <= 0.001f) 0f else (1f - (1f - db) / sb).coerceIn(0f, 1f)
                    }
                    LayerBlendMode.COLOR_DODGE -> {
                        outR = if (sr >= 0.999f) 1f else (dr / (1f - sr)).coerceIn(0f, 1f)
                        outG = if (sg >= 0.999f) 1f else (dg / (1f - sg)).coerceIn(0f, 1f)
                        outB = if (sb >= 0.999f) 1f else (db / (1f - sb)).coerceIn(0f, 1f)
                    }
                    LayerBlendMode.GLOW -> {
                        // Autodesk SketchBook Glow: intense incandescent core + chromatic dodge bloom
                        val sLuma = maxOf(sr, sg, sb)
                        val coreBoost = (sLuma * sLuma * 0.48f)
                        fun glowCh(b: Float, s: Float): Float {
                            val dodge = (s * s) / (1.02f - b * 0.88f).coerceAtLeast(0.05f)
                            return (b + s * 1.15f + dodge * 0.65f + coreBoost).coerceIn(0f, 1f)
                        }
                        outR = glowCh(dr, sr)
                        outG = glowCh(dg, sg)
                        outB = glowCh(db, sb)
                    }
                    LayerBlendMode.SOFT_GLOW -> {
                        // SketchBook Soft Glow: warm screen + soft-knee bloom without harsh clipping
                        val sLuma = (0.299f * sr + 0.587f * sg + 0.114f * sb)
                        val halo = sLuma * sLuma * 0.22f
                        fun softGlowCh(b: Float, s: Float): Float {
                            val screen = 1f - (1f - b) * (1f - s)
                            val bloom = (s * s) / (1.1f - b * 0.55f).coerceAtLeast(0.15f)
                            return (screen + bloom * 0.32f + halo).coerceIn(0f, 1f)
                        }
                        outR = softGlowCh(dr, sr)
                        outG = softGlowCh(dg, sg)
                        outB = softGlowCh(db, sb)
                    }
                    LayerBlendMode.SOFT_LIGHT -> {
                        fun softCh(b: Float, s: Float): Float {
                            return if (s <= 0.5f) {
                                b - (1f - 2f * s) * b * (1f - b)
                            } else {
                                val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b else sqrt(b)
                                b + (2f * s - 1f) * (d - b)
                            }
                        }
                        outR = softCh(dr, sr).coerceIn(0f, 1f)
                        outG = softCh(dg, sg).coerceIn(0f, 1f)
                        outB = softCh(db, sb).coerceIn(0f, 1f)
                    }
                    LayerBlendMode.HARD_LIGHT -> {
                        fun hardCh(b: Float, s: Float): Float {
                            return if (s <= 0.5f) 2f * b * s else 1f - 2f * (1f - b) * (1f - s)
                        }
                        outR = hardCh(dr, sr).coerceIn(0f, 1f)
                        outG = hardCh(dg, sg).coerceIn(0f, 1f)
                        outB = hardCh(db, sb).coerceIn(0f, 1f)
                    }
                    LayerBlendMode.HUE, LayerBlendMode.SATURATION, LayerBlendMode.COLOR, LayerBlendMode.LUMINOSITY -> {
                        ColorStudioState.colorToHslOut(dc, hslB)
                        ColorStudioState.colorToHslOut(sc, hslS)
                        val rh = if (mode == LayerBlendMode.HUE || mode == LayerBlendMode.COLOR) hslS[0] else hslB[0]
                        val rs = if (mode == LayerBlendMode.SATURATION || mode == LayerBlendMode.COLOR) hslS[1] else hslB[1]
                        val rl = if (mode == LayerBlendMode.LUMINOSITY) hslS[2] else hslB[2]
                        val resCol = ColorStudioState.hslToColor(rh, rs, rl)
                        outR = Color.red(resCol) / 255f
                        outG = Color.green(resCol) / 255f
                        outB = Color.blue(resCol) / 255f
                    }
                    else -> {
                        outR = sr; outG = sg; outB = sb
                    }
                }

                // Interpolate blended result by source effective alpha (sa)
                val finalR = ((dr * (1f - sa) + outR * sa) * 255f).roundToInt().coerceIn(0, 255)
                val finalG = ((dg * (1f - sa) + outG * sa) * 255f).roundToInt().coerceIn(0, 255)
                val finalB = ((db * (1f - sa) + outB * sa) * 255f).roundToInt().coerceIn(0, 255)
                val finalA = ((da + sa * (1f - da)) * 255f).roundToInt().coerceIn(0, 255)
                dstPixels[i] = (finalA shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
            dstBmp.setPixels(dstPixels, 0, w, 0, y, w, rows)
            y += rows
        }
    }
}

class LayerDockAndModalsController(private val hostView: SketchWorkspaceView) {

    var isLayerDockOpen: Boolean = false
    var isLayerDockPinned: Boolean = false
    var showLayerNamesInDock: Boolean = false
    var dockScrollY: Float = 0f
    var blendModeScrollY: Float = 0f
    var colorLabelScrollY: Float = 0f

    // Internal Layer Clipboard for Copy / Cut / Paste
    private var clipboardLayerBitmap: Bitmap? = null

    // Live Layer Adjust State
    var adjustBrightness: Float = 0f   // -100..100
    var adjustContrast: Float = 0f     // -100..100
    var adjustSaturation: Float = 0f   // -100..100
    private var adjustBackupBitmap: Bitmap? = null

    // Touch & Drag State
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastY: Float = 0f
    private var isScrollingDock: Boolean = false
    private var isDraggingDockLayer: Boolean = false
    private var draggedDockLayerIdx: Int = -1
    private var dragDockCurrentY: Float = 0f
    private var dockLongPressRunnable: Runnable? = null

    private var isDraggingOpacitySlider: Boolean = false
    private var draggingAdjustSliderId: String? = null
    private var isScrollingBlendModal: Boolean = false
    private var isScrollingColorLabelModal: Boolean = false

    val dockRect = RectF()
    private val dockCardsAreaRect = RectF()
    val modalOuterRect = RectF()
    private val opacitySliderRect = RectF()
    private val adjustSliderRects = mutableMapOf<String, RectF>()

    data class LayerUiHit(val id: String, val rect: RectF, val arg: Int = -1)
    private val dockHits = mutableListOf<LayerUiHit>()
    private val modalHits = mutableListOf<LayerUiHit>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        val prefs = hostView.context.getSharedPreferences("sketchbook_classic_prefs", Context.MODE_PRIVATE)
        showLayerNamesInDock = prefs.getBoolean("pref_show_layer_names", false)
        ColorLabelPalette.loadFromPrefs(hostView.context)
    }

    fun setShowLayerNamesPreference(enabled: Boolean) {
        showLayerNamesInDock = enabled
        hostView.context.getSharedPreferences("sketchbook_classic_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pref_show_layer_names", enabled)
            .apply()
    }

    fun isTouchInsideLayerDock(x: Float, y: Float): Boolean {
        return isLayerDockOpen && dockRect.contains(x, y)
    }

    fun isTouchInsideModalOrBottomCircles(x: Float, y: Float, viewW: Float, viewH: Float): Boolean {
        val sx = viewW / 1080f
        if (modalOuterRect.contains(x, y)) return true
        if (y >= viewH - 220f * sx && x in (190f * sx)..(890f * sx)) return true
        return false
    }

    // ==========================================================================
    // 1-TO-1 RIGHT-SIDE LAYER DOCK (Screenshot_20261027_162658.jpg & 163753.jpg)
    // ==========================================================================
    fun drawLayerDock(canvas: Canvas, viewW: Float, viewH: Float) {
        if (!isLayerDockOpen) {
            dockRect.setEmpty()
            return
        }
        dockHits.clear()
        val project = hostView.project
        project.ensureLayersInitialized()

        val sx = viewW / 1080f
        val sy = viewH / 2408f

        // Exact pixel coordinates from Screenshot_20261027_162658.jpg:
        // Dock X = 816..1080 (width = 264px), Dock Y (in view space) = 228..2083
        val dockLeft = 816f * sx
        val dockRight = viewW
        val dockTop = 228f * sy
        val dockBottom = 2083f * sy
        dockRect.set(dockLeft, dockTop, dockRight, dockBottom)

        // Subtle translucent track background matching Color.argb(239, 246, 246, 246)
        fillPaint.color = Color.argb(239, 246, 246, 246)
        canvas.drawRect(dockRect, fillPaint)

        // 1. Top '+' Header Bar (y_view = 228..346, height = 118 * sy)
        val headerH = 118f * sy
        val headerBot = dockTop + headerH
        fillPaint.color = Color.rgb(239, 239, 239)
        canvas.drawRect(dockLeft, dockTop, dockRight, headerBot, fillPaint)
        strokePaint.color = Color.rgb(224, 224, 224)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(dockLeft, headerBot, dockRight, headerBot, strokePaint)

        // Top-Left Pin Triangle Button (x = 816..872, y = dockTop..dockTop+56)
        val pinTriSize = 56f * sx
        val pinPath = Path().apply {
            moveTo(dockLeft, dockTop)
            lineTo(dockLeft + pinTriSize, dockTop)
            lineTo(dockLeft, dockTop + pinTriSize)
            close()
        }
        fillPaint.color = if (isLayerDockPinned) Color.rgb(20, 120, 216) else Color.rgb(225, 225, 225)
        canvas.drawPath(pinPath, fillPaint)
        drawMiniPushPin(
            canvas,
            cx = dockLeft + 18f * sx,
            cy = dockTop + 18f * sy,
            size = 20f * sx,
            color = if (isLayerDockPinned) Color.WHITE else Color.rgb(95, 95, 95)
        )
        dockHits.add(LayerUiHit("DOCK_TOGGLE_PIN", RectF(dockLeft, dockTop, dockLeft + 74f * sx, dockTop + 74f * sy)))

        // '+' Add Layer Icon centered at x = 947 * sx, cy = dockTop + headerH * 0.5f
        val plusCx = 947f * sx
        val plusCy = dockTop + headerH * 0.5f
        val plusHalf = 22f * sx
        strokePaint.color = Color.rgb(89, 89, 89)
        strokePaint.strokeWidth = 5.5f * sx
        canvas.drawLine(plusCx - plusHalf, plusCy, plusCx + plusHalf, plusCy, strokePaint)
        canvas.drawLine(plusCx, plusCy - plusHalf, plusCx, plusCy + plusHalf, strokePaint)
        dockHits.add(LayerUiHit("DOCK_ADD_LAYER", RectF(dockLeft + 74f * sx, dockTop, dockRight, headerBot)))

        // 2. Pinned Bottom Color Wheel Bar (y_view = dockBottom - 136 .. dockBottom)
        val botWheelH = 136f * sy
        val botWheelTop = dockBottom - botWheelH
        fillPaint.color = Color.rgb(239, 239, 239)
        canvas.drawRect(dockLeft, botWheelTop, dockRight, dockBottom, fillPaint)
        strokePaint.color = Color.rgb(225, 225, 225)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(dockLeft, botWheelTop, dockRight, botWheelTop, strokePaint)
        SketchIcons.drawTopIcon6ColorWheel(canvas, 947f * sx, (botWheelTop + dockBottom) * 0.5f, 156f * sx)
        dockHits.add(LayerUiHit("DOCK_BOTTOM_COLOR_WHEEL", RectF(dockLeft, botWheelTop, dockRight, dockBottom)))

        // 3. Pinned Background Layer Card above Bottom Color Wheel (width = 235 * sx, height = 158 * sy)
        val bgCardH = 158f * sy
        val bgCardBot = botWheelTop - 8f * sy
        val bgCardTop = bgCardBot - bgCardH
        val bgCardLeft = 824f * sx
        val bgCardRight = 1059f * sx
        val bgCardRect = RectF(bgCardLeft, bgCardTop, bgCardRight, bgCardBot)

        if (project.backgroundVisible) {
            fillPaint.color = project.backgroundColor
            canvas.drawRoundRect(bgCardRect, 4f * sx, 4f * sx, fillPaint)
        } else {
            // Show subtle checkerboard + tinted overlay when Background eye is disabled
            LayerStudioCompositor.drawBackgroundToCanvas(canvas, project, 0, 0, forExport = false)
            fillPaint.color = project.backgroundColor
            canvas.drawRoundRect(bgCardRect, 4f * sx, 4f * sx, fillPaint)
        }
        strokePaint.color = Color.rgb(215, 215, 225)
        strokePaint.strokeWidth = 2f * sx
        canvas.drawRoundRect(bgCardRect, 4f * sx, 4f * sx, strokePaint)

        // Contrast color for Eye icon & "Background" text on Background Card
        val bgLuma = (0.299f * Color.red(project.backgroundColor) +
                      0.587f * Color.green(project.backgroundColor) +
                      0.114f * Color.blue(project.backgroundColor))
        val bgFgColor = if (bgLuma < 140f) Color.WHITE else Color.rgb(65, 65, 65)

        val bgEyeCx = bgCardLeft + 46f * sx
        val bgEyeCy = bgCardTop + 42f * sy
        drawLayerEyeIcon(canvas, bgEyeCx, bgEyeCy, 42f * sx, visible = project.backgroundVisible, fgColor = bgFgColor)

        textPaint.color = bgFgColor
        textPaint.textSize = 27f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = false
        canvas.drawText("Background", bgCardRect.centerX(), bgCardBot - 22f * sy, textPaint)

        val bgEyeHit = RectF(bgCardLeft, bgCardTop, bgCardLeft + 92f * sx, bgCardTop + 86f * sy)
        dockHits.add(LayerUiHit("DOCK_BG_CARD_BODY", bgCardRect))
        dockHits.add(LayerUiHit("DOCK_BG_EYE", bgEyeHit))

        // 4. Scrollable Drawing Layer Cards Stack (between headerBot and bgCardTop - 8 * sy)
        val stackTop = headerBot + 8f * sy
        val stackBot = bgCardTop - 8f * sy
        dockCardsAreaRect.set(dockLeft, stackTop, dockRight, stackBot)

        val cardW = 250f * sx
        val cardH = 250f * sx
        val cardGap = 10f * sy
        val stepY = cardH + cardGap
        val totalStackH = project.layers.size * stepY
        val availStackH = (stackBot - stackTop).coerceAtLeast(cardH)
        val maxScroll = max(0f, totalStackH - availStackH)
        dockScrollY = dockScrollY.coerceIn(0f, maxScroll)

        // Bottom-aligned when total height is smaller than available dock space (matching 162658.jpg!)
        val startY = if (totalStackH < availStackH) {
            stackBot - totalStackH + cardGap * 0.5f
        } else {
            stackTop - dockScrollY
        }

        canvas.save()
        canvas.clipRect(dockCardsAreaRect)

        for (i in project.layers.indices) {
            if (isDraggingDockLayer && i == draggedDockLayerIdx) continue
            val cTop = startY + i * stepY
            if (cTop + cardH >= stackTop && cTop <= stackBot) {
                drawSingleLayerCard(canvas, project, i, 824f * sx, cTop, cardW, cardH, sx, sy, isFloating = false)
            }
        }

        if (isDraggingDockLayer && draggedDockLayerIdx in project.layers.indices) {
            val floatTop = (dragDockCurrentY - cardH * 0.5f).coerceIn(stackTop, stackBot - cardH)
            drawSingleLayerCard(canvas, project, draggedDockLayerIdx, 820f * sx, floatTop, cardW, cardH, sx, sy, isFloating = true)
        }

        canvas.restore()
    }

    private fun drawSingleLayerCard(
        canvas: Canvas,
        project: SketchProjectState,
        layerIdx: Int,
        left: Float,
        top: Float,
        cardW: Float,
        cardH: Float,
        sx: Float,
        sy: Float,
        isFloating: Boolean
    ) {
        val layer = project.layers[layerIdx]
        val isSelected = (layerIdx == project.selectedLayerIndex)
        val cardLeft = if (layer.clippingMask) left + 12f * sx else left
        val cardRect = RectF(cardLeft, top, left + cardW, top + cardH)

        // Draw Smart Cropped Square Thumbnail
        val thumb = layer.getOrUpdateThumbnail()
        canvas.drawBitmap(thumb, null, cardRect, bmpPaint)

        // Optional Layer Name Pill at Top of Card (controlled by Preferences -> Show Layer Names)
        if (showLayerNamesInDock) {
            val pillRect = RectF(cardRect.left + 72f * sx, cardRect.top + 8f * sy, cardRect.right - 8f * sx, cardRect.top + 46f * sy)
            fillPaint.color = Color.argb(185, 38, 40, 44)
            canvas.drawRoundRect(pillRect, 8f * sx, 8f * sx, fillPaint)
            subTextPaint.color = Color.WHITE
            subTextPaint.textSize = 22f * sx
            subTextPaint.textAlign = Paint.Align.LEFT
            val maxW = pillRect.width() - 16f * sx
            canvas.drawText(ellipsize(layer.name, subTextPaint, maxW), pillRect.left + 8f * sx, pillRect.centerY() + 8f * sy, subTextPaint)
        }

        // Border / Outline Color:
        // Uses the Color Label color if assigned (1..20), or classic SketchBook blue (#1477D4) when selected!
        val hasColorLabel = (layer.colorLabelIndex in 1..20)
        val labelColor = if (hasColorLabel) ColorLabelPalette.colors[layer.colorLabelIndex] else Color.rgb(20, 119, 212)

        if (isSelected) {
            if (hasColorLabel) {
                // Selected layer with Color Label: outer vivid label border + inner SketchBook selection frame
                strokePaint.color = labelColor
                strokePaint.strokeWidth = 8.5f * sx
                canvas.drawRect(
                    cardRect.left + 3f * sx,
                    cardRect.top + 3f * sy,
                    cardRect.right - 3f * sx,
                    cardRect.bottom - 3f * sy,
                    strokePaint
                )
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 2.5f * sx
                canvas.drawRect(
                    cardRect.left + 8.5f * sx,
                    cardRect.top + 8.5f * sy,
                    cardRect.right - 8.5f * sx,
                    cardRect.bottom - 8.5f * sy,
                    strokePaint
                )
            } else {
                // Exact #1477D4 6.5px selected border from 162658.jpg
                strokePaint.color = Color.rgb(20, 119, 212)
                strokePaint.strokeWidth = 6.5f * sx
                canvas.drawRect(
                    cardRect.left + 3f * sx,
                    cardRect.top + 3f * sy,
                    cardRect.right - 3f * sx,
                    cardRect.bottom - 3f * sy,
                    strokePaint
                )
            }
        } else {
            if (hasColorLabel) {
                // Unselected layer with Color Label: clean colored border outline!
                strokePaint.color = labelColor
                strokePaint.strokeWidth = 5.2f * sx
                canvas.drawRect(
                    cardRect.left + 2.5f * sx,
                    cardRect.top + 2.5f * sy,
                    cardRect.right - 2.5f * sx,
                    cardRect.bottom - 2.5f * sy,
                    strokePaint
                )
            } else {
                strokePaint.color = Color.rgb(225, 225, 225)
                strokePaint.strokeWidth = 2f * sx
                canvas.drawRect(cardRect, strokePaint)
            }
        }

        // Top-Left Eye Icon (rel x = 32, y = 30)
        val eyeCx = cardRect.left + 34f * sx
        val eyeCy = cardRect.top + 30f * sy
        drawLayerEyeIcon(canvas, eyeCx, eyeCy, 40f * sx, visible = layer.visible, fgColor = Color.rgb(100, 100, 100))

        // Bottom-Left Lock Transparency Icon (rel x = 30, y = cardH - 30)
        val lockCx = cardRect.left + 30f * sx
        val lockCy = cardRect.bottom - 30f * sy
        drawLockTransparencyBadge(canvas, lockCx, lockCy, 20f * sx, locked = layer.alphaLock, layerLocked = layer.locked)

        if (!isFloating) {
            val clippedTop = max(dockCardsAreaRect.top, cardRect.top)
            val clippedBot = min(dockCardsAreaRect.bottom, cardRect.bottom)
            if (clippedBot - clippedTop > 20f * sy) {
                dockHits.add(LayerUiHit("DOCK_LAYER_CARD", RectF(cardRect.left, clippedTop, cardRect.right, clippedBot), layerIdx))
                val eyeHit = RectF(cardRect.left, max(dockCardsAreaRect.top, cardRect.top), cardRect.left + 78f * sx, min(dockCardsAreaRect.bottom, cardRect.top + 72f * sy))
                if (eyeHit.height() > 15f * sy) {
                    dockHits.add(LayerUiHit("DOCK_LAYER_EYE", eyeHit, layerIdx))
                }
                val alphaHit = RectF(cardRect.left, max(dockCardsAreaRect.top, cardRect.bottom - 72f * sy), cardRect.left + 78f * sx, min(dockCardsAreaRect.bottom, cardRect.bottom))
                if (alphaHit.height() > 15f * sy) {
                    dockHits.add(LayerUiHit("DOCK_LAYER_ALPHALOCK", alphaHit, layerIdx))
                }
            }
        }
    }

    // ==========================================================================
    // LAYER SETTINGS, BLEND MODES, COLOR LABELS, ADJUST & PREFERENCES MODALS
    // ==========================================================================
    fun drawActiveLayerModal(canvas: Canvas, viewW: Float, viewH: Float, state: TopPopupState) {
        modalHits.clear()
        adjustSliderRects.clear()
        val sx = viewW / 1080f
        val sy = viewH / 2408f

        if (state == TopPopupState.PREFERENCES_SCREEN) {
            modalOuterRect.set(0f, 0f, viewW, viewH)
            drawPreferencesScreen(canvas, viewW, viewH, sx, sy)
            return
        }

        // Exact modal bounds from Screenshot_20261027_164530.jpg: x=127..951, y=127..2181
        val left = 127f * sx
        val right = 951f * sx
        val top = 127f * sy
        val bottom = viewH - 224f * sy
        modalOuterRect.set(left, top, right, bottom)

        when (state) {
            TopPopupState.LAYER_SETTINGS_MODAL -> drawLayerSettingsModal(canvas, left, top, right, bottom, sx, sy)
            TopPopupState.BLEND_MODE_MODAL -> drawBlendModeModal(canvas, left, top, right, bottom, sx, sy)
            TopPopupState.COLOR_LABEL_MODAL -> drawColorLabelModal(canvas, left, top, right, bottom, sx, sy)
            TopPopupState.LAYER_ADJUST_MODAL -> drawLayerAdjustModal(canvas, left, top, right, bottom, sx, sy)
            else -> {}
        }

        // Bottom 3 Floating Navigation Circles (Color Wheel | X Close | Brush Studio)
        drawModalBottomThreeCircles(canvas, viewW, viewH, sx, sy)
    }

    private fun drawLayerSettingsModal(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val project = hostView.project
        val layer = project.activeLayer()

        // Translucent dark card background matching 164530.jpg
        fillPaint.color = Color.argb(242, 49, 49, 51)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        // 1. Layer Name + Color Label Underline + Layer Index "1 / 3"
        val nameLeft = left + 54f * sx
        val nameBaselineY = top + 125f * sy
        textPaint.color = Color.rgb(225, 225, 225)
        textPaint.textSize = 44f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        val dispName = ellipsize(layer.name, textPaint, 680f * sx)
        canvas.drawText(dispName, nameLeft, nameBaselineY, textPaint)

        val nameW = max(120f * sx, textPaint.measureText(dispName))
        val underlineColor = if (layer.colorLabelIndex in 1..20) {
            ColorLabelPalette.colors[layer.colorLabelIndex]
        } else {
            Color.rgb(125, 125, 125)
        }
        fillPaint.color = underlineColor
        canvas.drawRect(nameLeft, nameBaselineY + 10f * sy, nameLeft + nameW, nameBaselineY + 17f * sy, fillPaint)
        modalHits.add(LayerUiHit("LS_RENAME_LAYER", RectF(nameLeft - 12f * sx, top + 65f * sy, nameLeft + nameW + 60f * sx, nameBaselineY + 30f * sy)))

        subTextPaint.color = Color.rgb(215, 215, 215)
        subTextPaint.textSize = 31f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        val totalCount = project.layers.size + 1 // +1 includes Background layer matching "1 / 3" in 164530.jpg!
        canvas.drawText("${project.selectedLayerIndex + 1} / $totalCount", left + 44f * sx, top + 212f * sy, subTextPaint)

        // 2. 4-Column Action Grid (exact X centers: 228, 435, 642, 848)
        val colCenters = floatArrayOf(
            left + 101f * sx,
            left + 308f * sx,
            left + 515f * sx,
            left + 721f * sx
        )

        val hasPaste = (clipboardLayerBitmap != null)
        val canMergeDown = (project.selectedLayerIndex < project.layers.lastIndex)
        val hasHiddenLayers = project.layers.any { !it.visible } && project.layers.count { it.visible } >= 1
        val canDelete = (project.layers.size > 1 && !layer.locked)

        data class GridAction(val id: String, val line1: String, val line2: String = "", val enabled: Boolean = true, val activeHighlight: Boolean = false)

        val row1 = listOf(
            GridAction("LS_ACT_COPY", "Copy", enabled = true),
            GridAction("LS_ACT_CUT", "Cut", enabled = !layer.locked),
            GridAction("LS_ACT_PASTE", "Paste", enabled = hasPaste),
            GridAction("LS_ACT_DUPLICATE", "Duplicate", enabled = true)
        )
        val row2 = listOf(
            GridAction("LS_ACT_CLEAR", "Clear", enabled = !layer.locked),
            GridAction("LS_ACT_MERGE", "Merge", enabled = canMergeDown && !layer.locked),
            GridAction("LS_ACT_DEL_HIDDEN", "Delete Hidden", "Layers", enabled = hasHiddenLayers),
            GridAction("LS_ACT_DELETE", "Delete", enabled = canDelete)
        )
        val row3 = listOf(
            GridAction("LS_ACT_LOCK_LAYER", "Lock Layer", enabled = true, activeHighlight = layer.locked),
            GridAction("LS_ACT_LOCK_TRANSP", "Lock", "Transparency", enabled = true, activeHighlight = layer.alphaLock),
            GridAction("LS_ACT_ADJUST", "Adjust", enabled = !layer.locked),
            GridAction("LS_ACT_CLIPPING_MASK", "Clipping Mask", enabled = canMergeDown, activeHighlight = layer.clippingMask)
        )
        val row4 = listOf(
            GridAction("LS_ACT_MAKE_GROUP", "Make Group", enabled = true)
        )

        val allRows = listOf(
            Pair(top + 315f * sy, row1),
            Pair(top + 510f * sy, row2),
            Pair(top + 708f * sy, row3),
            Pair(top + 906f * sy, row4)
        )

        for ((iconCy, actions) in allRows) {
            for ((cIdx, act) in actions.withIndex()) {
                val cx = colCenters[cIdx]
                val tint = when {
                    act.activeHighlight -> Color.rgb(75, 160, 245)
                    act.enabled -> Color.rgb(235, 235, 235)
                    else -> Color.rgb(108, 108, 108)
                }
                drawLayerActionIcon(canvas, act.id, cx, iconCy, 64f * sx, tint)

                subTextPaint.color = tint
                subTextPaint.textSize = 27f * sx
                subTextPaint.textAlign = Paint.Align.CENTER
                if (act.line2.isEmpty()) {
                    canvas.drawText(act.line1, cx, iconCy + 94f * sy, subTextPaint)
                } else {
                    canvas.drawText(act.line1, cx, iconCy + 78f * sy, subTextPaint)
                    canvas.drawText(act.line2, cx, iconCy + 110f * sy, subTextPaint)
                }

                val hitRect = RectF(cx - 96f * sx, iconCy - 56f * sy, cx + 96f * sx, iconCy + 124f * sy)
                modalHits.add(LayerUiHit(act.id, hitRect, if (act.enabled) 1 else 0))
            }
        }

        // 3. Opacity Slider (y = top + 1135..1240)
        val opacLabelY = top + 1142f * sy
        textPaint.color = Color.WHITE
        textPaint.textSize = 35f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Opacity:", left + 44f * sx, opacLabelY, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${layer.opacity.roundToInt()}", right - 44f * sx, opacLabelY, textPaint)

        val trackLeft = left + 55f * sx
        val trackRight = right - 55f * sx
        val trackY = top + 1220f * sy
        val f = (layer.opacity / 100f).coerceIn(0f, 1f)
        val knobX = trackLeft + (trackRight - trackLeft) * f

        strokePaint.color = Color.rgb(155, 155, 155)
        strokePaint.strokeWidth = 4.2f * sx
        canvas.drawLine(knobX, trackY, trackRight, trackY, strokePaint)

        strokePaint.color = Color.rgb(80, 154, 227)
        strokePaint.strokeWidth = 5.2f * sx
        canvas.drawLine(trackLeft, trackY, knobX, trackY, strokePaint)

        fillPaint.color = Color.WHITE
        canvas.drawCircle(knobX, trackY, 27f * sx, fillPaint)
        opacitySliderRect.set(trackLeft - 30f * sx, trackY - 48f * sy, trackRight + 30f * sx, trackY + 48f * sy)

        // 4. Blending Row Card (y = top + 1345..1452)
        val blendCard = RectF(left + 28f * sx, top + 1345f * sy, right - 28f * sx, top + 1452f * sy)
        fillPaint.color = Color.rgb(44, 44, 46)
        canvas.drawRoundRect(blendCard, 10f * sx, 10f * sx, fillPaint)
        strokePaint.color = Color.rgb(38, 38, 40)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(blendCard, 10f * sx, 10f * sx, strokePaint)

        // Intersecting circles icon on left of Blending row
        val bIconCx = blendCard.left + 44f * sx
        val bIconCy = blendCard.centerY()
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f * sx
        canvas.drawCircle(bIconCx - 9f * sx, bIconCy, 14f * sx, strokePaint)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(bIconCx + 9f * sx, bIconCy, 14f * sx, fillPaint)

        textPaint.color = Color.rgb(235, 235, 235)
        textPaint.textSize = 34f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Blending", blendCard.left + 104f * sx, bIconCy + 12f * sy, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(layer.blendMode.title, blendCard.right - 95f * sx, bIconCy + 12f * sy, textPaint)
        drawRightChevron(canvas, blendCard.right - 44f * sx, bIconCy, 18f * sx, Color.WHITE)
        modalHits.add(LayerUiHit("LS_OPEN_BLEND_MODES", blendCard))

        // 5. Color Label Row Card (y = top + 1482..1589)
        val labelCard = RectF(left + 28f * sx, top + 1482f * sy, right - 28f * sx, top + 1589f * sy)
        fillPaint.color = Color.rgb(44, 44, 46)
        canvas.drawRoundRect(labelCard, 10f * sx, 10f * sx, fillPaint)
        strokePaint.color = Color.rgb(38, 38, 40)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(labelCard, 10f * sx, 10f * sx, strokePaint)

        val lIconCx = labelCard.left + 44f * sx
        val lIconCy = labelCard.centerY()
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2.8f * sx
        canvas.drawRect(lIconCx - 20f * sx, lIconCy - 20f * sy, lIconCx + 20f * sx, lIconCy + 20f * sy, strokePaint)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(lIconCx + 7f * sx, lIconCy + 7f * sy, 6f * sx, fillPaint)

        textPaint.color = Color.rgb(235, 235, 235)
        textPaint.textSize = 34f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Color Label", labelCard.left + 104f * sx, lIconCy + 12f * sy, textPaint)

        val activeLabelIdx = layer.colorLabelIndex.coerceIn(0, 20)
        val dotColor = ColorLabelPalette.colors[activeLabelIdx]
        val dotCx = labelCard.right - 112f * sx
        fillPaint.color = dotColor
        canvas.drawCircle(dotCx, lIconCy, 15f * sx, fillPaint)
        if (activeLabelIdx > 0) {
            subTextPaint.color = Color.rgb(185, 185, 185)
            subTextPaint.textSize = 27f * sx
            subTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                ellipsize(ColorLabelPalette.customNames[activeLabelIdx], subTextPaint, 240f * sx),
                dotCx - 28f * sx,
                lIconCy + 10f * sy,
                subTextPaint
            )
        }
        drawRightChevron(canvas, labelCard.right - 44f * sx, lIconCy, 18f * sx, Color.WHITE)
        modalHits.add(LayerUiHit("LS_OPEN_COLOR_LABELS", labelCard))
    }

    // ==========================================================================
    // BLEND MODE MODAL (Screenshot_20261027_165419.jpg & 165420.jpg)
    // ==========================================================================
    private fun drawBlendModeModal(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val layer = hostView.project.activeLayer()
        fillPaint.color = Color.rgb(46, 46, 46)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        // Header Bar
        val headerH = 132f * sy
        val headerBot = top + headerH
        fillPaint.color = Color.rgb(49, 49, 49)
        canvas.drawRect(left, top, right, headerBot, fillPaint)

        SketchIcons.drawBackChevron(canvas, left + 58f * sx, top + headerH * 0.5f, 44f * sx, Color.WHITE)
        modalHits.add(LayerUiHit("BM_BACK", RectF(left, top, left + 140f * sx, headerBot)))

        textPaint.color = Color.rgb(225, 225, 225)
        textPaint.textSize = 40f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Blend Mode", (left + right) * 0.5f, top + headerH * 0.5f + 14f * sy, textPaint)

        val bodyRect = RectF(left, headerBot, right, bottom)
        val rowH = 114f * sy
        val groupGap = 24f * sy
        val modesByGroup = LayerBlendMode.values().groupBy { it.groupIndex }

        var totalH = 36f * sy
        for ((_, list) in modesByGroup) {
            totalH += list.size * rowH + groupGap
        }
        val maxScroll = max(0f, totalH - bodyRect.height())
        blendModeScrollY = blendModeScrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(bodyRect)

        var curY = headerBot + 16f * sy - blendModeScrollY
        val cardLeft = left + 38f * sx
        val cardRight = right - 38f * sx

        for ((_, groupModes) in modesByGroup) {
            val gHeight = groupModes.size * rowH
            val gRect = RectF(cardLeft, curY, cardRight, curY + gHeight)
            if (gRect.bottom >= headerBot && gRect.top <= bottom) {
                fillPaint.color = Color.rgb(51, 51, 51)
                canvas.drawRoundRect(gRect, 14f * sx, 14f * sx, fillPaint)

                for ((idx, m) in groupModes.withIndex()) {
                    val rTop = curY + idx * rowH
                    val rBot = rTop + rowH
                    val cy = (rTop + rBot) * 0.5f

                    textPaint.color = Color.rgb(238, 238, 238)
                    textPaint.textSize = 37f * sx
                    textPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(m.title, cardLeft + 55f * sx, cy + 13f * sy, textPaint)

                    if (layer.blendMode == m) {
                        strokePaint.color = Color.rgb(55, 140, 235)
                        strokePaint.strokeWidth = 5f * sx
                        val cx = cardRight - 96f * sx
                        canvas.drawLine(cx - 18f * sx, cy, cx - 4f * sx, cy + 14f * sy, strokePaint)
                        canvas.drawLine(cx - 4f * sx, cy + 14f * sy, cx + 24f * sx, cy - 14f * sy, strokePaint)
                    }

                    val hitTop = max(headerBot, rTop)
                    val hitBot = min(bottom, rBot)
                    if (hitBot - hitTop > 20f * sy) {
                        modalHits.add(LayerUiHit("BM_SELECT_MODE", RectF(cardLeft, hitTop, cardRight, hitBot), m.ordinal))
                    }
                }
            }
            curY += gHeight + groupGap
        }

        canvas.restore()
    }

    // ==========================================================================
    // COLOR LABEL MODAL (Screenshot_20261027_164916.jpg + 20 Renamable Labels)
    // ==========================================================================
    private fun drawColorLabelModal(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val layer = hostView.project.activeLayer()
        fillPaint.color = Color.rgb(49, 49, 49)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        val headerH = 132f * sy
        val headerBot = top + headerH
        fillPaint.color = Color.rgb(46, 46, 46)
        canvas.drawRect(left, top, right, headerBot, fillPaint)

        SketchIcons.drawBackChevron(canvas, left + 58f * sx, top + headerH * 0.5f, 44f * sx, Color.WHITE)
        modalHits.add(LayerUiHit("CL_BACK", RectF(left, top, left + 140f * sx, headerBot)))

        textPaint.color = Color.rgb(225, 225, 225)
        textPaint.textSize = 40f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Color Label", (left + right) * 0.5f, top + headerH * 0.5f + 14f * sy, textPaint)

        val bodyRect = RectF(left, headerBot, right, bottom)
        val rowH = 114f * sy
        val count = ColorLabelPalette.colors.size // 21 (None + 20 colors)
        val totalH = count * rowH + 36f * sy
        val maxScroll = max(0f, totalH - bodyRect.height())
        colorLabelScrollY = colorLabelScrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(bodyRect)

        var curY = headerBot + 16f * sy - colorLabelScrollY
        for (i in 0 until count) {
            val rTop = curY + i * rowH
            val rBot = rTop + rowH
            if (rBot >= headerBot && rTop <= bottom) {
                val cy = (rTop + rBot) * 0.5f
                val dotCx = left + 114f * sx
                fillPaint.color = ColorLabelPalette.colors[i]
                canvas.drawCircle(dotCx, cy, 17f * sx, fillPaint)
                if (i == 0) {
                    strokePaint.color = Color.rgb(180, 180, 180)
                    strokePaint.strokeWidth = 2f * sx
                    canvas.drawCircle(dotCx, cy, 17f * sx, strokePaint)
                }

                textPaint.color = Color.rgb(235, 235, 235)
                textPaint.textSize = 37f * sx
                textPaint.textAlign = Paint.Align.LEFT
                val labelStr = ellipsize(ColorLabelPalette.customNames[i], textPaint, 430f * sx)
                canvas.drawText(labelStr, left + 175f * sx, cy + 13f * sy, textPaint)

                // Custom Label Rename Button for colors 1..20
                if (i > 0) {
                    val editBox = RectF(right - 255f * sx, cy - 28f * sy, right - 155f * sx, cy + 28f * sy)
                    fillPaint.color = Color.rgb(64, 64, 68)
                    canvas.drawRoundRect(editBox, 8f * sx, 8f * sx, fillPaint)
                    subTextPaint.color = Color.rgb(200, 210, 225)
                    subTextPaint.textSize = 23f * sx
                    subTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("Rename", editBox.centerX(), cy + 8f * sy, subTextPaint)

                    val clipEditTop = max(headerBot, editBox.top)
                    val clipEditBot = min(bottom, editBox.bottom)
                    if (clipEditBot - clipEditTop > 16f * sy) {
                        modalHits.add(LayerUiHit("CL_RENAME_LABEL", RectF(editBox.left - 10f * sx, clipEditTop, editBox.right + 10f * sx, clipEditBot), i))
                    }
                }

                if (layer.colorLabelIndex == i) {
                    strokePaint.color = Color.rgb(55, 140, 235)
                    strokePaint.strokeWidth = 5f * sx
                    val cx = right - 88f * sx
                    canvas.drawLine(cx - 18f * sx, cy, cx - 4f * sx, cy + 14f * sy, strokePaint)
                    canvas.drawLine(cx - 4f * sx, cy + 14f * sy, cx + 24f * sx, cy - 14f * sy, strokePaint)
                }

                val hitTop = max(headerBot, rTop)
                val hitBot = min(bottom, rBot)
                if (hitBot - hitTop > 20f * sy) {
                    val selRight = if (i > 0) right - 265f * sx else right
                    modalHits.add(LayerUiHit("CL_SELECT_LABEL", RectF(left, hitTop, selRight, hitBot), i))
                }
            }
        }

        canvas.restore()
    }

    // ==========================================================================
    // LIVE LAYER ADJUST SUB-MODAL (Brightness, Contrast, Saturation, Invert, Flip)
    // ==========================================================================
    private fun drawLayerAdjustModal(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        fillPaint.color = Color.rgb(48, 48, 50)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        val headerH = 132f * sy
        val headerBot = top + headerH
        fillPaint.color = Color.rgb(42, 42, 44)
        canvas.drawRect(left, top, right, headerBot, fillPaint)

        SketchIcons.drawBackChevron(canvas, left + 58f * sx, top + headerH * 0.5f, 44f * sx, Color.WHITE)
        modalHits.add(LayerUiHit("ADJ_BACK", RectF(left, top, left + 140f * sx, headerBot)))

        textPaint.color = Color.WHITE
        textPaint.textSize = 40f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Adjust Layer", (left + right) * 0.5f, top + headerH * 0.5f + 14f * sy, textPaint)

        var curY = headerBot + 50f * sy
        curY = drawAdjustSlider(canvas, left, right, curY, sx, sy, "ADJ_BRIGHT", "Brightness", "${adjustBrightness.roundToInt()}", (adjustBrightness + 100f) / 200f)
        curY = drawAdjustSlider(canvas, left, right, curY + 24f * sy, sx, sy, "ADJ_CONTRAST", "Contrast", "${adjustContrast.roundToInt()}", (adjustContrast + 100f) / 200f)
        curY = drawAdjustSlider(canvas, left, right, curY + 24f * sy, sx, sy, "ADJ_SAT", "Saturation", "${adjustSaturation.roundToInt()}", (adjustSaturation + 100f) / 200f)

        curY += 60f * sy
        val btnW = (right - left - 120f * sx) / 3f
        val btnH = 104f * sy
        val actions = listOf(
            Pair("ADJ_INVERT", "Invert Colors"),
            Pair("ADJ_FLIP_H", "Flip Horiz"),
            Pair("ADJ_FLIP_V", "Flip Vert")
        )
        for ((idx, item) in actions.withIndex()) {
            val bx = left + 40f * sx + idx * (btnW + 20f * sx)
            val bRect = RectF(bx, curY, bx + btnW, curY + btnH)
            fillPaint.color = Color.rgb(64, 66, 72)
            canvas.drawRoundRect(bRect, 12f * sx, 12f * sx, fillPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 28f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(item.second, bRect.centerX(), bRect.centerY() + 10f * sy, textPaint)
            modalHits.add(LayerUiHit(item.first, bRect))
        }

        val resetRect = RectF(left, bottom - 130f * sy, right, bottom)
        strokePaint.color = Color.rgb(180, 180, 180)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(left, resetRect.top, right, resetRect.top, strokePaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 38f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Reset Adjustments", resetRect.centerX(), resetRect.centerY() + 14f * sy, textPaint)
        modalHits.add(LayerUiHit("ADJ_RESET", resetRect))
    }

    private fun drawAdjustSlider(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        sx: Float,
        sy: Float,
        id: String,
        label: String,
        valStr: String,
        fraction: Float
    ): Float {
        val f = fraction.coerceIn(0f, 1f)
        textPaint.color = Color.WHITE
        textPaint.textSize = 35f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, left + 44f * sx, top + 44f * sy, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(valStr, right - 44f * sx, top + 44f * sy, textPaint)

        val trackLeft = left + 48f * sx
        val trackRight = right - 48f * sx
        val trackY = top + 116f * sy
        val knobX = trackLeft + (trackRight - trackLeft) * f

        strokePaint.color = Color.rgb(155, 155, 155)
        strokePaint.strokeWidth = 4.2f * sx
        canvas.drawLine(knobX, trackY, trackRight, trackY, strokePaint)

        strokePaint.color = Color.rgb(80, 154, 227)
        strokePaint.strokeWidth = 5.2f * sx
        canvas.drawLine(trackLeft, trackY, knobX, trackY, strokePaint)

        fillPaint.color = Color.WHITE
        canvas.drawCircle(knobX, trackY, 26f * sx, fillPaint)
        adjustSliderRects[id] = RectF(trackLeft - 30f * sx, trackY - 48f * sy, trackRight + 30f * sx, trackY + 48f * sy)
        return top + 178f * sy
    }

    // ==========================================================================
    // 1-TO-1 PREFERENCES SCREEN (Screenshot_20261027_165102.jpg — 1 Setting Only)
    // ==========================================================================
    private fun drawPreferencesScreen(canvas: Canvas, viewW: Float, viewH: Float, sx: Float, sy: Float) {
        fillPaint.color = Color.WHITE
        canvas.drawRect(0f, 0f, viewW, viewH, fillPaint)

        // Top Bar (#F2F2F2, height = 132 * sy)
        val headerH = 132f * sy
        fillPaint.color = Color.rgb(242, 242, 242)
        canvas.drawRect(0f, 0f, viewW, headerH, fillPaint)
        strokePaint.color = Color.rgb(197, 197, 197)
        strokePaint.strokeWidth = 3f
        canvas.drawLine(0f, headerH, viewW, headerH, strokePaint)

        // "< Done" in SketchBook blue (#1374D2)
        val doneBlue = Color.rgb(19, 116, 210)
        SketchIcons.drawBackChevron(canvas, 50f * sx, headerH * 0.5f, 44f * sx, doneBlue)
        textPaint.color = doneBlue
        textPaint.textSize = 42f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        canvas.drawText("Done", 96f * sx, headerH * 0.5f + 15f * sy, textPaint)
        modalHits.add(LayerUiHit("PREF_DONE", RectF(0f, 0f, 260f * sx, headerH)))

        // "Preferences" centered
        textPaint.color = Color.rgb(15, 15, 15)
        textPaint.textSize = 46f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Preferences", viewW * 0.5f, headerH * 0.5f + 16f * sy, textPaint)

        // "V6.2.0" on right
        subTextPaint.color = Color.rgb(25, 25, 25)
        subTextPaint.textSize = 27f * sx
        subTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("V6.2.0", viewW - 42f * sx, headerH * 0.5f + 10f * sy, subTextPaint)

        // Section Header: "User Interface"
        textPaint.color = Color.rgb(15, 15, 15)
        textPaint.textSize = 43f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        val secY = headerH + 146f * sy
        canvas.drawText("User Interface", 42f * sx, secY, textPaint)
        textPaint.isFakeBoldText = false

        // Single Requested Toggle Row: "Layer Names"
        val rowTop = secY + 46f * sy
        val rowBot = rowTop + 148f * sy
        val rowCy = (rowTop + rowBot) * 0.5f

        textPaint.color = Color.rgb(20, 20, 20)
        textPaint.textSize = 42f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Layer Names", 42f * sx, rowCy + 15f * sy, textPaint)

        drawSketchbookSwitch(canvas, viewW - 88f * sx, rowCy, sx, sy, checked = showLayerNamesInDock)

        strokePaint.color = Color.rgb(218, 218, 218)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(0f, rowBot, viewW, rowBot, strokePaint)

        modalHits.add(LayerUiHit("PREF_TOGGLE_LAYER_NAMES", RectF(0f, rowTop, viewW, rowBot)))
    }

    private fun drawSketchbookSwitch(canvas: Canvas, centerX: Float, centerY: Float, sx: Float, sy: Float, checked: Boolean) {
        val trackW = 92f * sx
        val trackH = 40f * sy
        val trackRect = RectF(centerX - trackW * 0.5f, centerY - trackH * 0.5f, centerX + trackW * 0.5f, centerY + trackH * 0.5f)
        fillPaint.color = if (checked) Color.rgb(194, 220, 243) else Color.rgb(192, 192, 192)
        canvas.drawRoundRect(trackRect, trackH * 0.5f, trackH * 0.5f, fillPaint)

        val thumbR = 29f * sx
        val thumbX = if (checked) trackRect.right - thumbR * 0.65f else trackRect.left + thumbR * 0.65f
        fillPaint.color = if (checked) Color.rgb(19, 116, 210) else Color.rgb(236, 236, 236)
        canvas.drawCircle(thumbX, centerY, thumbR, fillPaint)
    }

    private fun drawModalBottomThreeCircles(canvas: Canvas, viewW: Float, viewH: Float, sx: Float, sy: Float) {
        val cy = viewH - 128f * sy
        val r = 61f * sx

        val leftCx = 291f * sx
        fillPaint.color = Color.rgb(33, 33, 33)
        canvas.drawCircle(leftCx, cy, r, fillPaint)
        SketchIcons.drawTopIcon6ColorWheel(canvas, leftCx, cy, r * 2.1f)
        modalHits.add(LayerUiHit("MODAL_BOT_COLOR", RectF(leftCx - r, cy - r, leftCx + r, cy + r)))

        val midCx = 539f * sx
        fillPaint.color = Color.rgb(33, 33, 33)
        canvas.drawCircle(midCx, cy, r, fillPaint)
        strokePaint.color = Color.rgb(69, 149, 230)
        strokePaint.strokeWidth = 5f * sx
        val d = 19f * sx
        canvas.drawLine(midCx - d, cy - d, midCx + d, cy + d, strokePaint)
        canvas.drawLine(midCx + d, cy - d, midCx - d, cy + d, strokePaint)
        modalHits.add(LayerUiHit("MODAL_BOT_CLOSE", RectF(midCx - r, cy - r, midCx + r, cy + r)))

        val rightCx = 787f * sx
        fillPaint.color = Color.rgb(33, 33, 33)
        canvas.drawCircle(rightCx, cy, r, fillPaint)
        fillPaint.color = Color.rgb(69, 149, 230)
        strokePaint.color = Color.rgb(69, 149, 230)
        strokePaint.strokeWidth = r * 0.07f
        val s = r * 0.55f
        val p = Path().apply {
            moveTo(rightCx, cy - s * 0.72f)
            lineTo(rightCx - s * 0.38f, cy + s * 0.18f)
            lineTo(rightCx + s * 0.38f, cy + s * 0.18f)
            close()
        }
        canvas.drawPath(p, fillPaint)
        canvas.drawLine(rightCx - s * 0.75f, cy + s * 0.48f, rightCx + s * 0.75f, cy + s * 0.48f, strokePaint)
        canvas.drawCircle(rightCx, cy + s * 0.48f, s * 0.16f, fillPaint)
        modalHits.add(LayerUiHit("MODAL_BOT_BRUSH", RectF(rightCx - r, cy - r, rightCx + r, cy + r)))
    }

    // ==========================================================================
    // TOUCH HANDLING FOR DOCK & LAYER MODALS
    // ==========================================================================
    fun onDockTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val sx = hostView.width / 1080f
        val project = hostView.project

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastY = y
                isScrollingDock = false
                isDraggingDockLayer = false
                draggedDockLayerIdx = -1

                val hit = findDockHit(x, y)
                if (hit != null && hit.id == "DOCK_LAYER_CARD") {
                    val idx = hit.arg
                    val r = Runnable {
                        isDraggingDockLayer = true
                        draggedDockLayerIdx = idx
                        dragDockCurrentY = y
                        hostView.parent?.requestDisallowInterceptTouchEvent(true)
                        hostView.invalidate()
                    }
                    dockLongPressRunnable = r
                    hostView.postDelayed(r, 340L)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = y - lastY
                lastY = y

                if (isDraggingDockLayer && draggedDockLayerIdx in project.layers.indices) {
                    dragDockCurrentY = y
                    val stepY = 260f * sx
                    val totalStackH = project.layers.size * stepY
                    val availH = dockCardsAreaRect.height().coerceAtLeast(250f * sx)
                    val startY = if (totalStackH < availH) {
                        dockCardsAreaRect.bottom - totalStackH + 5f * sx
                    } else {
                        dockCardsAreaRect.top - dockScrollY
                    }
                    val targetIdx = ((y - startY) / stepY).toInt().coerceIn(0, project.layers.lastIndex)
                    if (targetIdx != draggedDockLayerIdx) {
                        project.pushUndoSnapshot()
                        val moved = project.layers.removeAt(draggedDockLayerIdx)
                        project.layers.add(targetIdx, moved)
                        project.selectedLayerIndex = targetIdx
                        draggedDockLayerIdx = targetIdx
                        project.isDirty = true
                    }
                    hostView.invalidate()
                    return true
                }

                if (abs(y - downY) > 16f * sx || abs(x - downX) > 16f * sx) {
                    cancelDockLongPress()
                    if (dockCardsAreaRect.contains(downX, downY)) {
                        isScrollingDock = true
                        dockScrollY -= dy
                        hostView.invalidate()
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelDockLongPress()
                if (isDraggingDockLayer) {
                    isDraggingDockLayer = false
                    draggedDockLayerIdx = -1
                    hostView.invalidate()
                    return true
                }
                if (!isScrollingDock && abs(x - downX) < 28f * sx && abs(y - downY) < 28f * sx) {
                    handleDockTap(x, y)
                }
                isScrollingDock = false
                hostView.invalidate()
                return true
            }
        }
        return true
    }

    private fun cancelDockLongPress() {
        dockLongPressRunnable?.let { hostView.removeCallbacks(it) }
        dockLongPressRunnable = null
    }

    private fun findDockHit(x: Float, y: Float): LayerUiHit? {
        for (i in dockHits.size - 1 downTo 0) {
            if (dockHits[i].rect.contains(x, y)) return dockHits[i]
        }
        return null
    }

    private fun handleDockTap(x: Float, y: Float) {
        val hit = findDockHit(x, y) ?: return
        val project = hostView.project
        project.ensureLayersInitialized()

        when (hit.id) {
            "DOCK_TOGGLE_PIN" -> {
                isLayerDockPinned = !isLayerDockPinned
                hostView.showBanner(if (isLayerDockPinned) "Layer menu pinned" else "Layer menu unpinned")
            }
            "DOCK_ADD_LAYER" -> {
                val maxAllowed = SketchProjectState.calculateMaxLayers(project.width, project.height)
                if (project.layers.size >= maxAllowed) {
                    hostView.showBanner("Maximum layers ($maxAllowed) reached for this canvas size")
                } else {
                    project.addNewLayerOnTop()
                    dockScrollY = 0f
                }
            }
            "DOCK_LAYER_EYE" -> {
                val idx = hit.arg
                if (idx in project.layers.indices) {
                    val l = project.layers[idx]
                    l.visible = !l.visible
                    project.isDirty = true
                }
            }
            "DOCK_LAYER_ALPHALOCK" -> {
                val idx = hit.arg
                if (idx in project.layers.indices) {
                    val l = project.layers[idx]
                    l.alphaLock = !l.alphaLock
                    project.isDirty = true
                }
            }
            "DOCK_LAYER_CARD" -> {
                val idx = hit.arg
                if (idx in project.layers.indices) {
                    if (project.selectedLayerIndex != idx) {
                        // Clicking an unselected layer SELECTS IT FIRST without opening Layer Settings!
                        project.selectedLayerIndex = idx
                    } else {
                        // Clicking the ALREADY-SELECTED layer pops up Layer Settings!
                        hostView.popupState = TopPopupState.LAYER_SETTINGS_MODAL
                    }
                }
            }
            "DOCK_BG_EYE" -> {
                project.backgroundVisible = !project.backgroundVisible
                project.isDirty = true
            }
            "DOCK_BG_CARD_BODY", "DOCK_BOTTOM_COLOR_WHEEL" -> {
                hostView.brushAndColorModal.syncBgColorFromProject(project.backgroundColor)
                hostView.popupState = TopPopupState.BG_COLOR_MENU
            }
        }
    }

    fun onModalTouchEvent(event: MotionEvent, state: TopPopupState): Boolean {
        val x = event.x
        val y = event.y
        val sx = hostView.width / 1080f
        val project = hostView.project
        val layer = project.activeLayer()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastY = y
                isDraggingOpacitySlider = false
                draggingAdjustSliderId = null
                isScrollingBlendModal = false
                isScrollingColorLabelModal = false

                if (state == TopPopupState.LAYER_SETTINGS_MODAL && opacitySliderRect.contains(x, y)) {
                    project.pushUndoSnapshot()
                    isDraggingOpacitySlider = true
                    updateOpacityFromTouch(x, layer)
                    hostView.invalidate()
                    return true
                }
                if (state == TopPopupState.LAYER_ADJUST_MODAL) {
                    for ((id, r) in adjustSliderRects) {
                        if (r.contains(x, y)) {
                            draggingAdjustSliderId = id
                            updateAdjustSliderFromTouch(id, x)
                            hostView.invalidate()
                            return true
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = y - lastY
                lastY = y

                if (isDraggingOpacitySlider) {
                    updateOpacityFromTouch(x, layer)
                    hostView.invalidate()
                    return true
                }
                if (draggingAdjustSliderId != null) {
                    updateAdjustSliderFromTouch(draggingAdjustSliderId!!, x)
                    hostView.invalidate()
                    return true
                }
                if (abs(y - downY) > 15f * sx) {
                    if (state == TopPopupState.BLEND_MODE_MODAL) {
                        isScrollingBlendModal = true
                        blendModeScrollY -= dy
                        hostView.invalidate()
                        return true
                    }
                    if (state == TopPopupState.COLOR_LABEL_MODAL) {
                        isScrollingColorLabelModal = true
                        colorLabelScrollY -= dy
                        hostView.invalidate()
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDraggingOpacitySlider || draggingAdjustSliderId != null || isScrollingBlendModal || isScrollingColorLabelModal
                isDraggingOpacitySlider = false
                draggingAdjustSliderId = null
                isScrollingBlendModal = false
                isScrollingColorLabelModal = false

                if (!wasDragging && abs(x - downX) < 28f * sx && abs(y - downY) < 28f * sx) {
                    handleModalTap(x, y, state)
                }
                hostView.invalidate()
                return true
            }
        }
        return true
    }

    private fun updateOpacityFromTouch(touchX: Float, layer: SketchLayer) {
        val f = ((touchX - opacitySliderRect.left) / opacitySliderRect.width()).coerceIn(0f, 1f)
        layer.opacity = (f * 100f).roundToInt().toFloat()
        hostView.project.isDirty = true
    }

    private fun handleModalTap(x: Float, y: Float, state: TopPopupState) {
        var hit: LayerUiHit? = null
        for (i in modalHits.size - 1 downTo 0) {
            if (modalHits[i].rect.contains(x, y)) {
                hit = modalHits[i]
                break
            }
        }
        if (hit == null) return

        val project = hostView.project
        val layer = project.activeLayer()

        when (hit.id) {
            "MODAL_BOT_COLOR" -> {
                cleanupAdjustBackup(commit = true)
                hostView.popupState = TopPopupState.COLOR_MENU
            }
            "MODAL_BOT_BRUSH" -> {
                cleanupAdjustBackup(commit = true)
                hostView.popupState = TopPopupState.BRUSH_MENU
            }
            "MODAL_BOT_CLOSE" -> {
                when (state) {
                    TopPopupState.BLEND_MODE_MODAL, TopPopupState.COLOR_LABEL_MODAL -> {
                        hostView.popupState = TopPopupState.LAYER_SETTINGS_MODAL
                    }
                    TopPopupState.LAYER_ADJUST_MODAL -> {
                        cleanupAdjustBackup(commit = true)
                        hostView.popupState = TopPopupState.LAYER_SETTINGS_MODAL
                    }
                    else -> {
                        hostView.popupState = TopPopupState.NONE
                    }
                }
            }
            "PREF_DONE" -> {
                hostView.popupState = TopPopupState.NONE
            }
            "PREF_TOGGLE_LAYER_NAMES" -> {
                setShowLayerNamesPreference(!showLayerNamesInDock)
            }
            "LS_RENAME_LAYER" -> {
                hostView.showSimpleInputModal("Rename Layer", layer.name) { newName ->
                    if (newName.isNotBlank()) {
                        layer.name = newName.trim()
                        project.isDirty = true
                    }
                    hostView.invalidate()
                }
            }
            "LS_ACT_COPY" -> {
                clipboardLayerBitmap?.let { if (!it.isRecycled) it.recycle() }
                clipboardLayerBitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                hostView.showBanner("Copied '${layer.name}'")
            }
            "LS_ACT_CUT" -> {
                if (hit.arg == 1) {
                    project.pushUndoSnapshot()
                    clipboardLayerBitmap?.let { if (!it.isRecycled) it.recycle() }
                    clipboardLayerBitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    layer.bitmap.eraseColor(Color.TRANSPARENT)
                    layer.isThumbnailDirty = true
                    project.isDirty = true
                    hostView.showBanner("Cut '${layer.name}'")
                } else {
                    hostView.showBanner("Layer is locked")
                }
            }
            "LS_ACT_PASTE" -> {
                val clip = clipboardLayerBitmap
                if (clip != null && !clip.isRecycled) {
                    project.pushUndoSnapshot()
                    val pasted = SketchLayer(
                        id = "layer_${System.currentTimeMillis()}_p",
                        name = "${layer.name} Paste",
                        bitmap = clip.copy(Bitmap.Config.ARGB_8888, true)
                    )
                    project.layers.add(project.selectedLayerIndex, pasted)
                    project.isDirty = true
                    hostView.popupState = TopPopupState.NONE
                    hostView.showBanner("Pasted as new layer")
                }
            }
            "LS_ACT_DUPLICATE" -> {
                project.pushUndoSnapshot()
                val dup = SketchLayer(
                    id = "layer_${System.currentTimeMillis()}_dup",
                    name = "${layer.name} Copy",
                    bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true),
                    visible = layer.visible,
                    alphaLock = layer.alphaLock,
                    locked = false,
                    clippingMask = layer.clippingMask,
                    opacity = layer.opacity,
                    blendMode = layer.blendMode,
                    colorLabelIndex = layer.colorLabelIndex,
                    temporalStrokesJson = layer.temporalStrokesJson
                )
                project.layers.add(project.selectedLayerIndex, dup)
                project.isDirty = true
                hostView.popupState = TopPopupState.NONE
                hostView.showBanner("Duplicated '${layer.name}'")
            }
            "LS_ACT_CLEAR" -> {
                if (hit.arg == 1) {
                    project.pushUndoSnapshot()
                    layer.bitmap.eraseColor(Color.TRANSPARENT)
                    layer.isThumbnailDirty = true
                    project.isDirty = true
                    hostView.popupState = TopPopupState.NONE
                    hostView.showBanner("Cleared '${layer.name}'")
                } else {
                    hostView.showBanner("Layer is locked")
                }
            }
            "LS_ACT_MERGE" -> {
                if (hit.arg == 1 && project.selectedLayerIndex < project.layers.lastIndex) {
                    project.pushUndoSnapshot()
                    val topLayer = project.layers[project.selectedLayerIndex]
                    val belowLayer = project.layers[project.selectedLayerIndex + 1]
                    val c = Canvas(belowLayer.bitmap)
                    val srcBmp = if (topLayer.clippingMask) {
                        val tmp = Bitmap.createBitmap(topLayer.bitmap.width, topLayer.bitmap.height, Bitmap.Config.ARGB_8888)
                        val tc = Canvas(tmp)
                        tc.drawBitmap(topLayer.bitmap, 0f, 0f, null)
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                        tc.drawBitmap(belowLayer.bitmap, 0f, 0f, p)
                        tmp
                    } else {
                        topLayer.bitmap
                    }
                    LayerStudioCompositor.compositeSingleLayer(
                        dstCanvas = c,
                        dstBmp = belowLayer.bitmap,
                        srcBmp = srcBmp,
                        mode = topLayer.blendMode,
                        opacityNorm = (topLayer.opacity / 100f).coerceIn(0f, 1f)
                    )
                    if (srcBmp !== topLayer.bitmap && !srcBmp.isRecycled) srcBmp.recycle()
                    belowLayer.isThumbnailDirty = true
                    val removed = project.layers.removeAt(project.selectedLayerIndex)
                    if (!removed.bitmap.isRecycled) removed.bitmap.recycle()
                    project.selectedLayerIndex = project.selectedLayerIndex.coerceIn(0, project.layers.lastIndex)
                    project.isDirty = true
                    hostView.popupState = TopPopupState.NONE
                    hostView.showBanner("Merged down into '${belowLayer.name}'")
                }
            }
            "LS_ACT_DEL_HIDDEN" -> {
                if (hit.arg == 1) {
                    project.pushUndoSnapshot()
                    val currentId = layer.id
                    val toRemove = project.layers.filter { !it.visible }
                    for (r in toRemove) {
                        project.layers.remove(r)
                        if (!r.bitmap.isRecycled) r.bitmap.recycle()
                    }
                    project.ensureLayersInitialized()
                    val newIdx = project.layers.indexOfFirst { it.id == currentId }
                    project.selectedLayerIndex = if (newIdx >= 0) newIdx else 0
                    project.isDirty = true
                    hostView.popupState = TopPopupState.NONE
                    hostView.showBanner("Deleted ${toRemove.size} hidden layer(s)")
                }
            }
            "LS_ACT_DELETE" -> {
                if (hit.arg == 1 && project.layers.size > 1) {
                    project.pushUndoSnapshot()
                    val removed = project.layers.removeAt(project.selectedLayerIndex)
                    if (!removed.bitmap.isRecycled) removed.bitmap.recycle()
                    project.selectedLayerIndex = project.selectedLayerIndex.coerceIn(0, project.layers.lastIndex)
                    project.isDirty = true
                    hostView.popupState = TopPopupState.NONE
                    hostView.showBanner("Deleted '${removed.name}'")
                }
            }
            "LS_ACT_LOCK_LAYER" -> {
                layer.locked = !layer.locked
                project.isDirty = true
            }
            "LS_ACT_LOCK_TRANSP" -> {
                layer.alphaLock = !layer.alphaLock
                project.isDirty = true
            }
            "LS_ACT_CLIPPING_MASK" -> {
                if (project.selectedLayerIndex < project.layers.lastIndex) {
                    layer.clippingMask = !layer.clippingMask
                    project.isDirty = true
                } else {
                    hostView.showBanner("Clipping Mask requires a layer below")
                }
            }
            "LS_ACT_ADJUST" -> {
                if (hit.arg == 1) {
                    project.pushUndoSnapshot()
                    adjustBrightness = 0f
                    adjustContrast = 0f
                    adjustSaturation = 0f
                    adjustBackupBitmap?.let { if (!it.isRecycled) it.recycle() }
                    adjustBackupBitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    hostView.popupState = TopPopupState.LAYER_ADJUST_MODAL
                } else {
                    hostView.showBanner("Layer is locked")
                }
            }
            "LS_ACT_MAKE_GROUP" -> {
                hostView.showBanner("Layer Groups coming in next stage")
            }
            "LS_OPEN_BLEND_MODES" -> {
                hostView.popupState = TopPopupState.BLEND_MODE_MODAL
            }
            "LS_OPEN_COLOR_LABELS" -> {
                hostView.popupState = TopPopupState.COLOR_LABEL_MODAL
            }
            "BM_BACK", "CL_BACK" -> {
                hostView.popupState = TopPopupState.LAYER_SETTINGS_MODAL
            }
            "BM_SELECT_MODE" -> {
                val modes = LayerBlendMode.values()
                if (hit.arg in modes.indices) {
                    project.pushUndoSnapshot()
                    layer.blendMode = modes[hit.arg]
                    project.isDirty = true
                    hostView.popupState = TopPopupState.LAYER_SETTINGS_MODAL
                }
            }
            "CL_SELECT_LABEL" -> {
                if (hit.arg in ColorLabelPalette.colors.indices) {
                    layer.colorLabelIndex = hit.arg
                    project.isDirty = true
                    hostView.popupState = TopPopupState.LAYER_SETTINGS_MODAL
                }
            }
            "CL_RENAME_LABEL" -> {
                val idx = hit.arg
                if (idx in 1 until ColorLabelPalette.customNames.size) {
                    hostView.showSimpleInputModal("Rename Color Label", ColorLabelPalette.customNames[idx]) { newLabel ->
                        if (newLabel.isNotBlank()) {
                            ColorLabelPalette.customNames[idx] = newLabel.trim()
                            ColorLabelPalette.saveToPrefs(hostView.context)
                            project.isDirty = true
                        }
                        hostView.invalidate()
                    }
                }
            }
            "ADJ_BACK" -> {
                cleanupAdjustBackup(commit = true)
                hostView.popupState = TopPopupState.LAYER_SETTINGS_MODAL
            }
            "ADJ_RESET" -> {
                adjustBrightness = 0f
                adjustContrast = 0f
                adjustSaturation = 0f
                applyLiveAdjustFilter()
            }
            "ADJ_INVERT" -> {
                val src = adjustBackupBitmap ?: layer.bitmap
                val w = src.width; val h = src.height
                val row = IntArray(w)
                for (ry in 0 until h) {
                    src.getPixels(row, 0, w, 0, ry, w, 1)
                    for (rx in 0 until w) {
                        val c = row[rx]
                        val a = c ushr 24
                        if (a > 0) {
                            val r = 255 - ((c shr 16) and 0xFF)
                            val g = 255 - ((c shr 8) and 0xFF)
                            val b = 255 - (c and 0xFF)
                            row[rx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    src.setPixels(row, 0, w, 0, ry, w, 1)
                }
                applyLiveAdjustFilter()
            }
            "ADJ_FLIP_H" -> {
                val src = adjustBackupBitmap ?: layer.bitmap
                val m = Matrix().apply { preScale(-1f, 1f) }
                val flipped = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                val c = Canvas(src)
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                c.drawBitmap(flipped, 0f, 0f, null)
                flipped.recycle()
                applyLiveAdjustFilter()
            }
            "ADJ_FLIP_V" -> {
                val src = adjustBackupBitmap ?: layer.bitmap
                val m = Matrix().apply { preScale(1f, -1f) }
                val flipped = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                val c = Canvas(src)
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                c.drawBitmap(flipped, 0f, 0f, null)
                flipped.recycle()
                applyLiveAdjustFilter()
            }
        }
    }

    private fun updateAdjustSliderFromTouch(id: String, touchX: Float) {
        val rect = adjustSliderRects[id] ?: return
        val f = ((touchX - rect.left) / rect.width()).coerceIn(0f, 1f)
        val v = (-100f + f * 200f).roundToInt().toFloat()
        when (id) {
            "ADJ_BRIGHT" -> adjustBrightness = v
            "ADJ_CONTRAST" -> adjustContrast = v
            "ADJ_SAT" -> adjustSaturation = v
        }
        applyLiveAdjustFilter()
    }

    private fun applyLiveAdjustFilter() {
        val backup = adjustBackupBitmap ?: return
        val layer = hostView.project.activeLayer()
        val dst = layer.bitmap
        val c = Canvas(dst)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val satMatrix = ColorMatrix().apply {
            setSaturation((1f + adjustSaturation / 100f).coerceAtLeast(0f))
        }
        val scale = 1f + (adjustContrast / 100f) * 0.85f
        val translate = (-0.5f * scale + 0.5f) * 255f + adjustBrightness * 1.6f
        val bcMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        satMatrix.postConcat(bcMatrix)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(satMatrix)
        }
        c.drawBitmap(backup, 0f, 0f, p)
        layer.isThumbnailDirty = true
        hostView.project.isDirty = true
    }

    fun cleanupAdjustBackup(commit: Boolean) {
        val backup = adjustBackupBitmap ?: return
        if (!commit) {
            val layer = hostView.project.activeLayer()
            val c = Canvas(layer.bitmap)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.drawBitmap(backup, 0f, 0f, null)
            layer.isThumbnailDirty = true
        }
        if (!backup.isRecycled) backup.recycle()
        adjustBackupBitmap = null
    }

    // ==========================================================================
    // PRECISION VECTOR ICONS FOR LAYER DOCK & LAYER SETTINGS
    // ==========================================================================
    private fun drawMiniPushPin(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        fillPaint.color = color
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.14f
        c.drawLine(cx - size * 0.28f, cy - size * 0.42f, cx + size * 0.28f, cy - size * 0.42f, strokePaint)
        c.drawRect(cx - size * 0.16f, cy - size * 0.42f, cx + size * 0.16f, cy + size * 0.04f, fillPaint)
        c.drawLine(cx - size * 0.32f, cy + size * 0.04f, cx + size * 0.32f, cy + size * 0.04f, strokePaint)
        c.drawLine(cx, cy + size * 0.04f, cx, cy + size * 0.48f, strokePaint)
    }

    private fun drawLayerEyeIcon(c: Canvas, cx: Float, cy: Float, size: Float, visible: Boolean, fgColor: Int) {
        val w = size * 0.52f
        val h = size * 0.30f
        val eyePath = Path().apply {
            moveTo(cx - w, cy)
            quadTo(cx, cy - h * 1.4f, cx + w, cy)
            quadTo(cx, cy + h * 1.4f, cx - w, cy)
            close()
        }
        fillPaint.color = Color.WHITE
        c.drawPath(eyePath, fillPaint)
        strokePaint.color = fgColor
        strokePaint.strokeWidth = size * 0.095f
        c.drawPath(eyePath, strokePaint)

        fillPaint.color = fgColor
        c.drawCircle(cx, cy, size * 0.15f, fillPaint)

        if (!visible) {
            strokePaint.color = fgColor
            strokePaint.strokeWidth = size * 0.11f
            c.drawLine(cx - w * 0.85f, cy + h * 1.15f, cx + w * 0.85f, cy - h * 1.15f, strokePaint)
        }
    }

    private fun drawLockTransparencyBadge(c: Canvas, cx: Float, cy: Float, r: Float, locked: Boolean, layerLocked: Boolean) {
        fillPaint.color = Color.WHITE
        c.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = Color.rgb(105, 105, 105)
        strokePaint.strokeWidth = r * 0.15f
        c.drawCircle(cx, cy, r, strokePaint)

        if (locked || layerLocked) {
            // Padlock inside circle matching Screenshot_20261027_163753.jpg
            fillPaint.color = if (layerLocked) Color.rgb(20, 119, 212) else Color.rgb(100, 100, 100)
            strokePaint.color = fillPaint.color
            strokePaint.strokeWidth = r * 0.16f
            val bw = r * 0.48f
            val bh = r * 0.38f
            c.drawRect(cx - bw, cy - bh * 0.2f, cx + bw, cy + bh * 1.4f, fillPaint)
            c.drawArc(RectF(cx - bw * 0.65f, cy - bh * 1.35f, cx + bw * 0.65f, cy + bh * 0.1f), 180f, 180f, false, strokePaint)
        } else {
            // 2x2 Rotated/Orthogonal Checkerboard inside circle matching Screenshot_20261027_162658.jpg
            val clipPath = Path().apply { addCircle(cx, cy, r * 0.88f, Path.Direction.CW) }
            c.save()
            c.clipPath(clipPath)
            fillPaint.color = Color.rgb(100, 100, 100)
            c.drawRect(cx - r, cy - r, cx, cy, fillPaint)
            c.drawRect(cx, cy, cx + r, cy + r, fillPaint)
            c.restore()
        }
    }

    private fun drawRightChevron(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.26f
        c.drawLine(cx - size * 0.4f, cy - size * 0.85f, cx + size * 0.45f, cy, strokePaint)
        c.drawLine(cx - size * 0.4f, cy + size * 0.85f, cx + size * 0.45f, cy, strokePaint)
    }

    private fun drawLayerActionIcon(c: Canvas, id: String, cx: Float, cy: Float, size: Float, tint: Int) {
        strokePaint.color = tint
        strokePaint.strokeWidth = size * 0.07f
        fillPaint.color = tint
        val s = size * 0.46f

        when (id) {
            "LS_ACT_COPY" -> {
                c.drawRect(cx - s * 0.9f, cy - s * 0.65f, cx + s * 0.2f, cy + s * 0.45f, strokePaint)
                c.drawRect(cx - s * 0.55f, cy - s * 0.35f, cx + s * 0.55f, cy + s * 0.75f, strokePaint)
                val px = cx + s * 1.15f
                val py = cy + s * 0.32f
                c.drawCircle(px, py, s * 0.38f, fillPaint)
                strokePaint.color = Color.rgb(49, 49, 51)
                c.drawLine(px - s * 0.2f, py, px + s * 0.2f, py, strokePaint)
                c.drawLine(px, py - s * 0.2f, px, py + s * 0.2f, strokePaint)
            }
            "LS_ACT_CUT" -> {
                c.drawCircle(cx - s * 0.38f, cy + s * 0.55f, s * 0.28f, strokePaint)
                c.drawCircle(cx + s * 0.38f, cy + s * 0.55f, s * 0.28f, strokePaint)
                c.drawLine(cx - s * 0.22f, cy + s * 0.35f, cx + s * 0.45f, cy - s * 0.75f, strokePaint)
                c.drawLine(cx + s * 0.22f, cy + s * 0.35f, cx - s * 0.45f, cy - s * 0.75f, strokePaint)
            }
            "LS_ACT_PASTE" -> {
                c.drawRect(cx - s * 0.65f, cy - s * 0.65f, cx + s * 0.35f, cy + s * 0.65f, strokePaint)
                c.drawRect(cx - s * 0.35f, cy - s * 0.85f, cx + s * 0.05f, cy - s * 0.55f, strokePaint)
                c.drawRect(cx - s * 0.25f, cy - s * 0.25f, cx + s * 0.65f, cy + s * 0.85f, strokePaint)
            }
            "LS_ACT_DUPLICATE" -> {
                c.drawRect(cx - s * 0.65f, cy - s * 0.65f, cx + s * 0.3f, cy + s * 0.55f, strokePaint)
                c.drawRect(cx - s * 0.3f, cy - s * 0.35f, cx + s * 0.65f, cy + s * 0.85f, fillPaint)
            }
            "LS_ACT_CLEAR" -> {
                c.drawRect(cx - s * 0.65f, cy - s * 0.65f, cx + s * 0.25f, cy + s * 0.55f, strokePaint)
                c.drawRect(cx - s * 0.2f, cy - s * 0.25f, cx + s * 0.6f, cy + s * 0.75f, fillPaint)
            }
            "LS_ACT_MERGE" -> {
                c.drawRect(cx - s * 0.75f, cy - s * 0.65f, cx + s * 0.35f, cy + s * 0.65f, strokePaint)
                c.drawLine(cx - s * 0.2f, cy - s * 0.25f, cx - s * 0.2f, cy + s * 0.35f, strokePaint)
                c.drawLine(cx - s * 0.45f, cy + s * 0.1f, cx - s * 0.2f, cy + s * 0.35f, strokePaint)
                c.drawLine(cx + s * 0.05f, cy + s * 0.1f, cx - s * 0.2f, cy + s * 0.35f, strokePaint)
                val px = cx + s * 1.15f
                val py = cy + s * 0.25f
                c.drawCircle(px, py, s * 0.38f, fillPaint)
                strokePaint.color = Color.rgb(49, 49, 51)
                c.drawLine(px - s * 0.2f, py, px + s * 0.2f, py, strokePaint)
                c.drawLine(px, py - s * 0.2f, px, py + s * 0.2f, strokePaint)
            }
            "LS_ACT_DEL_HIDDEN", "LS_ACT_DELETE" -> {
                SketchIcons.drawTrashIcon(c, cx, cy + s * 0.15f, size * 0.9f, tint)
            }
            "LS_ACT_LOCK_LAYER" -> {
                val bw = s * 0.55f
                val bh = s * 0.48f
                c.drawRoundRect(RectF(cx - bw, cy - bh * 0.1f, cx + bw, cy + bh * 1.35f), 4f, 4f, fillPaint)
                c.drawArc(RectF(cx - bw * 0.65f, cy - bh * 1.35f, cx + bw * 0.65f, cy + bh * 0.2f), 180f, 185f, false, strokePaint)
            }
            "LS_ACT_LOCK_TRANSP" -> {
                val r = s * 0.92f
                c.drawCircle(cx, cy, r, strokePaint)
                val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
                c.save()
                c.clipPath(clip)
                c.drawRect(cx - r, cy - r, cx, cy, fillPaint)
                c.drawRect(cx, cy, cx + r, cy + r, fillPaint)
                c.restore()
            }
            "LS_ACT_ADJUST" -> {
                c.drawRect(cx - s * 0.75f, cy - s * 0.75f, cx + s * 0.65f, cy + s * 0.15f, strokePaint)
                val mp = Path().apply {
                    moveTo(cx - s * 0.7f, cy + s * 0.12f)
                    lineTo(cx - s * 0.25f, cy - s * 0.32f)
                    lineTo(cx + s * 0.15f, cy + s * 0.02f)
                    lineTo(cx + s * 0.42f, cy - s * 0.18f)
                    lineTo(cx + s * 0.62f, cy + s * 0.12f)
                    close()
                }
                c.drawPath(mp, fillPaint)
                c.drawLine(cx - s * 0.85f, cy + s * 0.65f, cx + s * 0.75f, cy + s * 0.65f, strokePaint)
                c.drawCircle(cx - s * 0.15f, cy + s * 0.65f, s * 0.22f, fillPaint)
                val chx = cx + s * 1.25f
                val chy = cy + s * 0.15f
                c.drawCircle(chx, chy, s * 0.38f, fillPaint)
                strokePaint.color = Color.rgb(49, 49, 51)
                c.drawLine(chx - s * 0.1f, chy - s * 0.16f, chx + s * 0.12f, chy, strokePaint)
                c.drawLine(chx - s * 0.1f, chy + s * 0.16f, chx + s * 0.12f, chy, strokePaint)
            }
            "LS_ACT_CLIPPING_MASK" -> {
                c.drawLine(cx - s * 0.65f, cy - s * 0.45f, cx + s * 0.15f, cy - s * 0.45f, strokePaint)
                c.drawLine(cx + s * 0.15f, cy - s * 0.45f, cx + s * 0.15f, cy + s * 0.55f, strokePaint)
                val p = Path().apply {
                    moveTo(cx + s * 0.15f, cy + s * 0.75f)
                    lineTo(cx - s * 0.22f, cy + s * 0.32f)
                    lineTo(cx + s * 0.52f, cy + s * 0.32f)
                    close()
                }
                c.drawPath(p, fillPaint)
            }
            "LS_ACT_MAKE_GROUP" -> {
                SketchIcons.drawFolderIcon(c, cx, cy, size * 1.05f, tint)
            }
        }
    }

    private fun ellipsize(str: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(str) <= maxW) return str
        var s = str
        while (s.length > 1 && paint.measureText("$s...") > maxW) {
            s = s.dropLast(1)
        }
        return "$s..."
    }
}
