package com.sketchbook.oss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class BrushAndColorModal(private val hostView: SketchWorkspaceView) {

    val brushSets: MutableList<SketchBrushSet> = DefaultBrushCatalog.createDefaultSets()
    var selectedSetIndex: Int = 0
    var selectedBrushIndex: Int = 14 // "Duplicate" in Basic set by default
    val colorState = ColorStudioState()
    val bgColorState = ColorStudioState().apply {
        setFromColorInt(Color.WHITE, recordPrevious = false)
    }

    var brushTopTab: BrushModalTopTab = BrushModalTopTab.LIBRARY_GRID
    var settingsSubTab: BrushSettingsSubTab = BrushSettingsSubTab.BASIC
    var subOverlay: BrushSubOverlay = BrushSubOverlay.NONE
    var expandedAdvancedSection: Int = -1

    var libraryScrollY: Float = 0f
    var advancedScrollY: Float = 0f

    private var draggingSliderId: String? = null
    private var isDraggingHueRing: Boolean = false
    private var isDraggingSlDiamond: Boolean = false
    private var isScrollingLibrary: Boolean = false
    private var isScrollingAdvanced: Boolean = false
    private var activeBgModeInTouch: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastY: Float = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    data class ModalHit(val id: String, val rect: RectF, val arg1: Int = -1, val arg2: Int = -1)
    private val hits = mutableListOf<ModalHit>()

    val outerCardRect = RectF()
    private val libraryScrollBounds = RectF()
    private val advancedScrollBounds = RectF()
    private val sliderRects = mutableMapOf<String, RectF>()

    private var wheelCx = 540f
    private var wheelCy = 925f
    private var wheelOuterR = 325f
    private var wheelInnerR = 220f
    private var diamondHalfDiag = 192f

    fun syncBgColorFromProject(c: Int) {
        bgColorState.setFromColorInt(c, recordPrevious = false)
    }

    fun selectedSet(): SketchBrushSet {
        if (brushSets.isEmpty()) brushSets.addAll(DefaultBrushCatalog.createDefaultSets())
        selectedSetIndex = selectedSetIndex.coerceIn(0, brushSets.lastIndex)
        return brushSets[selectedSetIndex]
    }

    fun selectedBrush(): SketchBrushItem {
        val set = selectedSet()
        if (set.brushes.isEmpty()) {
            set.brushes.add(SketchBrushItem("b_fallback", "Standard Pen", BrushIconStyle.TECHNICAL_PEN))
        }
        selectedBrushIndex = selectedBrushIndex.coerceIn(0, set.brushes.lastIndex)
        return set.brushes[selectedBrushIndex]
    }

    fun isTouchInsideModalOrBottomBar(x: Float, y: Float, viewW: Float, viewH: Float): Boolean {
        val sx = viewW / 1080f
        if (outerCardRect.contains(x, y)) return true
        if (y >= viewH - 220f * sx && x in (190f * sx)..(890f * sx)) return true
        return false
    }

    fun draw(canvas: Canvas, viewW: Float, viewH: Float, isColorMode: Boolean, isBgColorMode: Boolean = false) {
        hits.clear()
        sliderRects.clear()

        val sx = viewW / 1080f
        val sy = viewH / 2408f

        val innerLeft = 124f * sx
        val innerRight = viewW - 124f * sx
        val innerTop = 124f * sy
        val innerBottom = viewH - 224f * sy
        outerCardRect.set(innerLeft, innerTop, innerRight, innerBottom)

        when {
            isBgColorMode -> drawBgColorAndStudioModalContent(canvas, innerLeft, innerTop, innerRight, innerBottom, sx, sy)
            isColorMode -> drawColorModalContent(canvas, innerLeft, innerTop, innerRight, innerBottom, sx, sy, bgMode = false)
            else -> drawBrushModalContent(canvas, innerLeft, innerTop, innerRight, innerBottom, sx, sy)
        }

        drawBottomThreeCircles(canvas, viewW, viewH, sx, sy, isColorMode || isBgColorMode)
    }

    // ==========================================================================
    // 2-TAB LIVE BACKGROUND COLOR & BACKGROUND STUDIO MODAL (Screenshot 164119.jpg)
    // ==========================================================================
    private fun drawBgColorAndStudioModalContent(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val panelW = right - left
        val topTabH = 132f * sy
        val midX = left + panelW * 0.5f

        // ONLY 2 TOP SUBMENUS:
        // Tab 0 (Left): Live Background Color Wheel (164119.jpg)
        // Tab 1 (Right): Live Background Studio (Textures, Grids & Lighting)
        val tab0Rect = RectF(left, top, midX, top + topTabH)
        val tab1Rect = RectF(midX, top, right, top + topTabH)

        val activeTab = bgColorState.topTabIndex.coerceIn(0, 1)
        fillPaint.color = if (activeTab == 0) Color.rgb(20, 120, 216) else Color.rgb(142, 142, 142)
        canvas.drawRect(tab0Rect, fillPaint)

        fillPaint.color = if (activeTab == 1) Color.rgb(20, 120, 216) else Color.rgb(142, 142, 142)
        canvas.drawRect(tab1Rect, fillPaint)

        SketchIcons.drawTopIcon6ColorWheel(canvas, tab0Rect.centerX(), tab0Rect.centerY(), topTabH * 0.95f)
        drawBgStudioTopIcon(canvas, tab1Rect.centerX(), tab1Rect.centerY(), 54f * sx)

        hits.add(ModalHit("BG_TOP_TAB", tab0Rect, 0))
        hits.add(ModalHit("BG_TOP_TAB", tab1Rect, 1))

        if (activeTab == 0) {
            drawColorWheelBody(canvas, left, top + topTabH, right, bottom, sx, sy, state = bgColorState, isBgMode = true)
        } else {
            drawLiveBackgroundStudioBody(canvas, left, top + topTabH, right, bottom, sx, sy)
        }
    }

    private fun drawLiveBackgroundStudioBody(
        canvas: Canvas,
        left: Float,
        bodyTop: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val panelW = right - left
        val cfg = hostView.project.backgroundConfig
        fillPaint.color = Color.rgb(48, 48, 50)
        canvas.drawRect(left, bodyTop, right, bottom, fillPaint)

        // 1. Live Mini Preview Banner at Top of Background Studio (y = bodyTop .. bodyTop + 168)
        val prevH = 168f * sy
        val prevRect = RectF(left + 28f * sx, bodyTop + 20f * sy, right - 28f * sx, bodyTop + prevH)
        canvas.save()
        canvas.clipRect(prevRect)
        canvas.translate(prevRect.left, prevRect.top)
        LayerStudioCompositor.drawBackgroundToCanvas(
            targetCanvas = canvas,
            project = hostView.project,
            w = prevRect.width().roundToInt().coerceAtLeast(1),
            h = prevRect.height().roundToInt().coerceAtLeast(1),
            forExport = false
        )
        canvas.restore()
        strokePaint.color = Color.rgb(95, 95, 100)
        strokePaint.strokeWidth = 2.5f * sx
        canvas.drawRect(prevRect, strokePaint)

        // 2. 3 Sub-Tabs: SURFACE | GRIDS | LIGHTING
        val subTabTop = prevRect.bottom + 20f * sy
        val subTabH = 112f * sy
        val subTabBot = subTabTop + subTabH
        fillPaint.color = Color.rgb(39, 39, 41)
        canvas.drawRect(left, subTabTop, right, subTabBot, fillPaint)

        val slotW = panelW / 3f
        val titles = arrayOf("SURFACE", "GRIDS", "LIGHTING")
        for (i in 0..2) {
            val sLeft = left + i * slotW
            val sRect = RectF(sLeft, subTabTop, sLeft + slotW, subTabBot)
            textPaint.color = if (cfg.studioSubTab == i) Color.rgb(65, 148, 238) else Color.rgb(125, 125, 130)
            textPaint.textSize = 33f * sx
            textPaint.isFakeBoldText = (cfg.studioSubTab == i)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(titles[i], sRect.centerX(), subTabTop + 68f * sy, textPaint)
            textPaint.isFakeBoldText = false
            if (i < 2) {
                strokePaint.color = Color.rgb(30, 30, 32)
                strokePaint.strokeWidth = 2f
                canvas.drawLine(sRect.right, subTabTop + 18f * sy, sRect.right, subTabBot - 18f * sy, strokePaint)
            }
            hits.add(ModalHit("BG_STUDIO_SUBTAB", sRect, i))
        }

        // 3. Preset Cards Grid + Live Sliders
        var curY = subTabBot + 28f * sy
        val padX = 32f * sx
        val cols = 3
        val gap = 18f * sx
        val cardW = (panelW - padX * 2f - gap * (cols - 1)) / cols
        val cardH = 142f * sy

        when (cfg.studioSubTab) {
            0 -> { // SURFACE (Paper & Canvas Textures)
                val surfNames = arrayOf("Smooth", "Cold Press", "Canvas", "Charcoal", "Halftone", "Parchment")
                for (i in 0..5) {
                    val r = i / 3
                    val c = i % 3
                    val cx = left + padX + c * (cardW + gap)
                    val cy = curY + r * (cardH + gap)
                    val cRect = RectF(cx, cy, cx + cardW, cy + cardH)
                    val isSel = (cfg.surfacePreset == i)

                    fillPaint.color = if (isSel) Color.rgb(62, 74, 92) else Color.rgb(38, 38, 40)
                    canvas.drawRoundRect(cRect, 12f * sx, 12f * sx, fillPaint)
                    strokePaint.color = if (isSel) Color.rgb(65, 148, 238) else Color.rgb(72, 72, 76)
                    strokePaint.strokeWidth = if (isSel) 4.2f * sx else 2f * sx
                    canvas.drawRoundRect(cRect, 12f * sx, 12f * sx, strokePaint)

                    drawSurfacePresetVectorIcon(canvas, cRect.centerX(), cRect.top + 52f * sy, 36f * sx, i, isSel)

                    subTextPaint.color = if (isSel) Color.WHITE else Color.rgb(185, 185, 190)
                    subTextPaint.textSize = 24f * sx
                    subTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(surfNames[i], cRect.centerX(), cRect.bottom - 20f * sy, subTextPaint)
                    hits.add(ModalHit("BG_SELECT_SURFACE", cRect, i))
                }
                curY += 2 * (cardH + gap) + 28f * sy
                curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_SURF_INTENSITY", "Grain Intensity", "${cfg.grainIntensity.roundToInt()}%", cfg.grainIntensity / 100f)
                curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_SURF_SCALE", "Texture Scale", "${cfg.textureScale.roundToInt()}%", (cfg.textureScale - 25f) / 225f)
                drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_SURF_WARMTH", "Warmth / Cool Tint", "${cfg.warmthTint.roundToInt()}", (cfg.warmthTint + 50f) / 100f)
            }

            1 -> { // GRIDS (Precision Architectural & Illustration Grids)
                val gridNames = arrayOf("None", "Square", "Isometric", "Perspective", "Dot Matrix", "Thirds")
                for (i in 0..5) {
                    val r = i / 3
                    val c = i % 3
                    val cx = left + padX + c * (cardW + gap)
                    val cy = curY + r * (cardH + gap)
                    val cRect = RectF(cx, cy, cx + cardW, cy + cardH)
                    val isSel = (cfg.gridPreset == i)

                    fillPaint.color = if (isSel) Color.rgb(62, 74, 92) else Color.rgb(38, 38, 40)
                    canvas.drawRoundRect(cRect, 12f * sx, 12f * sx, fillPaint)
                    strokePaint.color = if (isSel) Color.rgb(65, 148, 238) else Color.rgb(72, 72, 76)
                    strokePaint.strokeWidth = if (isSel) 4.2f * sx else 2f * sx
                    canvas.drawRoundRect(cRect, 12f * sx, 12f * sx, strokePaint)

                    drawGridPresetVectorIcon(canvas, cRect.centerX(), cRect.top + 52f * sy, 36f * sx, i, isSel)

                    subTextPaint.color = if (isSel) Color.WHITE else Color.rgb(185, 185, 190)
                    subTextPaint.textSize = 24f * sx
                    subTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(gridNames[i], cRect.centerX(), cRect.bottom - 20f * sy, subTextPaint)
                    hits.add(ModalHit("BG_SELECT_GRID", cRect, i))
                }
                curY += 2 * (cardH + gap) + 28f * sy
                curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_GRID_SPACING", "Grid Spacing", "${cfg.gridSpacing.roundToInt()} px", (cfg.gridSpacing - 20f) / 280f)
                curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_GRID_OPACITY", "Grid Opacity", "${cfg.gridOpacity.roundToInt()}%", (cfg.gridOpacity - 5f) / 95f)
                curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_GRID_SUBDIV", "Subdivisions / Density", "${cfg.gridSubdivisions.roundToInt()}", (cfg.gridSubdivisions - 1f) / 7f)

                // Invert Contrast Toggle Chip
                val chipRect = RectF(left + 40f * sx, curY + 8f * sy, right - 40f * sx, curY + 92f * sy)
                fillPaint.color = if (cfg.invertGridContrast) Color.rgb(55, 116, 205) else Color.rgb(62, 62, 66)
                canvas.drawRoundRect(chipRect, 10f * sx, 10f * sx, fillPaint)
                textPaint.color = Color.WHITE
                textPaint.textSize = 30f * sx
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(if (cfg.invertGridContrast) "Grid Line Contrast: Inverted" else "Grid Line Contrast: Auto", chipRect.centerX(), chipRect.centerY() + 10f * sy, textPaint)
                hits.add(ModalHit("BG_TOGGLE_GRID_CONTRAST", chipRect))
            }

            else -> { // LIGHTING (Vignette & Horizon Gradient)
                val lightNames = arrayOf("Flat", "Vignette", "Horizon", "Spotlight")
                val lCardW = (panelW - padX * 2f - gap) / 2f
                for (i in 0..3) {
                    val r = i / 2
                    val c = i % 2
                    val cx = left + padX + c * (lCardW + gap)
                    val cy = curY + r * (cardH + gap)
                    val cRect = RectF(cx, cy, cx + lCardW, cy + cardH)
                    val isSel = (cfg.lightingPreset == i)

                    fillPaint.color = if (isSel) Color.rgb(62, 74, 92) else Color.rgb(38, 38, 40)
                    canvas.drawRoundRect(cRect, 12f * sx, 12f * sx, fillPaint)
                    strokePaint.color = if (isSel) Color.rgb(65, 148, 238) else Color.rgb(72, 72, 76)
                    strokePaint.strokeWidth = if (isSel) 4.2f * sx else 2f * sx
                    canvas.drawRoundRect(cRect, 12f * sx, 12f * sx, strokePaint)

                    drawLightingPresetVectorIcon(canvas, cRect.centerX(), cRect.top + 52f * sy, 36f * sx, i, isSel)

                    subTextPaint.color = if (isSel) Color.WHITE else Color.rgb(185, 185, 190)
                    subTextPaint.textSize = 26f * sx
                    subTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(lightNames[i], cRect.centerX(), cRect.bottom - 20f * sy, subTextPaint)
                    hits.add(ModalHit("BG_SELECT_LIGHTING", cRect, i))
                }
                curY += 2 * (cardH + gap) + 28f * sy
                curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_LIGHT_STRENGTH", "Intensity", "${cfg.vignetteStrength.roundToInt()}%", cfg.vignetteStrength / 100f)
                curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_LIGHT_SOFT", "Softness / Spread", "${cfg.vignetteSoftness.roundToInt()}%", (cfg.vignetteSoftness - 10f) / 90f)
                drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "BG_LIGHT_ELEV", "Focus Elevation", "${cfg.focusElevation.roundToInt()}%", cfg.focusElevation / 100f)
            }
        }

        // Pinned Reset Background Button
        val resetTop = bottom - 124f * sy
        fillPaint.color = Color.rgb(48, 48, 50)
        canvas.drawRect(left, resetTop, right, bottom, fillPaint)
        strokePaint.color = Color.rgb(185, 185, 185)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(left, resetTop, right, resetTop, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Reset Background Studio", (left + right) * 0.5f, resetTop + 76f * sy, textPaint)
        hits.add(ModalHit("BG_RESET_STUDIO", RectF(left, resetTop, right, bottom)))
    }

    private fun drawBgStudioTopIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(232, 232, 232)
        strokePaint.strokeWidth = size * 0.065f
        val r = size * 0.44f
        c.drawRect(cx - r, cy - r, cx + r, cy + r, strokePaint)
        c.drawLine(cx - r, cy - r * 0.33f, cx + r, cy - r * 0.33f, strokePaint)
        c.drawLine(cx - r, cy + r * 0.33f, cx + r, cy + r * 0.33f, strokePaint)
        c.drawLine(cx - r * 0.33f, cy - r, cx - r * 0.33f, cy + r, strokePaint)
        c.drawLine(cx + r * 0.33f, cy - r, cx + r * 0.33f, cy + r, strokePaint)
    }

    private fun drawSurfacePresetVectorIcon(c: Canvas, cx: Float, cy: Float, r: Float, preset: Int, selected: Boolean) {
        val col = if (selected) Color.rgb(95, 175, 255) else Color.rgb(195, 195, 200)
        strokePaint.color = col
        strokePaint.strokeWidth = r * 0.1f
        fillPaint.color = col
        c.drawRect(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f, strokePaint)
        when (preset) {
            1 -> { // Cold press waves
                c.drawLine(cx - r * 0.65f, cy - r * 0.2f, cx + r * 0.65f, cy - r * 0.2f, strokePaint)
                c.drawLine(cx - r * 0.45f, cy + r * 0.25f, cx + r * 0.65f, cy + r * 0.25f, strokePaint)
            }
            2 -> { // Woven canvas crosshatch
                for (k in -1..1) {
                    val o = k * r * 0.42f
                    c.drawLine(cx + o, cy - r * 0.6f, cx + o, cy + r * 0.6f, strokePaint)
                    c.drawLine(cx - r * 0.8f, cy + o * 0.7f, cx + r * 0.8f, cy + o * 0.7f, strokePaint)
                }
            }
            3 -> { // Charcoal laid lines
                for (k in -2..2) {
                    val y = cy + k * r * 0.24f
                    c.drawLine(cx - r * 0.8f, y, cx + r * 0.8f, y, strokePaint)
                }
            }
            4 -> { // Halftone dots
                for (dx in -1..1) for (dy in -1..1) {
                    c.drawCircle(cx + dx * r * 0.48f, cy + dy * r * 0.36f, r * 0.1f, fillPaint)
                }
            }
            5 -> { // Parchment diagonal grain
                c.drawLine(cx - r * 0.7f, cy + r * 0.4f, cx + r * 0.7f, cy - r * 0.4f, strokePaint)
                c.drawLine(cx - r * 0.3f, cy + r * 0.5f, cx + r * 0.7f, cy - r * 0.1f, strokePaint)
            }
        }
    }

    private fun drawGridPresetVectorIcon(c: Canvas, cx: Float, cy: Float, r: Float, preset: Int, selected: Boolean) {
        val col = if (selected) Color.rgb(95, 175, 255) else Color.rgb(195, 195, 200)
        strokePaint.color = col
        strokePaint.strokeWidth = r * 0.09f
        fillPaint.color = col
        when (preset) {
            0 -> {
                c.drawCircle(cx, cy, r * 0.65f, strokePaint)
                c.drawLine(cx - r * 0.45f, cy + r * 0.45f, cx + r * 0.45f, cy - r * 0.45f, strokePaint)
            }
            1 -> {
                c.drawRect(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f, strokePaint)
                c.drawLine(cx, cy - r * 0.7f, cx, cy + r * 0.7f, strokePaint)
                c.drawLine(cx - r, cy, cx + r, cy, strokePaint)
            }
            2 -> {
                c.drawLine(cx - r, cy - r * 0.6f, cx + r, cy + r * 0.6f, strokePaint)
                c.drawLine(cx - r, cy + r * 0.6f, cx + r, cy - r * 0.6f, strokePaint)
                c.drawLine(cx, cy - r * 0.7f, cx, cy + r * 0.7f, strokePaint)
            }
            3 -> {
                c.drawLine(cx - r, cy, cx + r, cy, strokePaint)
                c.drawLine(cx, cy, cx - r * 0.85f, cy + r * 0.7f, strokePaint)
                c.drawLine(cx, cy, cx + r * 0.85f, cy + r * 0.7f, strokePaint)
                c.drawLine(cx, cy, cx - r * 0.85f, cy - r * 0.7f, strokePaint)
                c.drawLine(cx, cy, cx + r * 0.85f, cy - r * 0.7f, strokePaint)
            }
            4 -> {
                for (dx in -1..1) for (dy in -1..1) {
                    c.drawCircle(cx + dx * r * 0.55f, cy + dy * r * 0.45f, r * 0.11f, fillPaint)
                }
            }
            5 -> {
                c.drawRect(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f, strokePaint)
                c.drawLine(cx - r * 0.33f, cy - r * 0.7f, cx - r * 0.33f, cy + r * 0.7f, strokePaint)
                c.drawLine(cx + r * 0.33f, cy - r * 0.7f, cx + r * 0.33f, cy + r * 0.7f, strokePaint)
            }
        }
    }

    private fun drawLightingPresetVectorIcon(c: Canvas, cx: Float, cy: Float, r: Float, preset: Int, selected: Boolean) {
        val col = if (selected) Color.rgb(95, 175, 255) else Color.rgb(195, 195, 200)
        strokePaint.color = col
        strokePaint.strokeWidth = r * 0.1f
        fillPaint.color = col
        c.drawRect(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f, strokePaint)
        when (preset) {
            1 -> c.drawCircle(cx, cy, r * 0.45f, strokePaint)
            2 -> c.drawLine(cx - r, cy, cx + r, cy, strokePaint)
            3 -> c.drawCircle(cx, cy, r * 0.35f, fillPaint)
        }
    }

    // ==========================================================================
    // 1-TO-1 BRUSH MODAL
    // ==========================================================================
    private fun drawBrushModalContent(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val panelW = right - left
        val topTabH = 132f * sy

        val midX = left + panelW * 0.5f
        val leftTabRect = RectF(left, top, midX, top + topTabH)
        val rightTabRect = RectF(midX, top, right, top + topTabH)

        fillPaint.color = if (brushTopTab == BrushModalTopTab.LIBRARY_GRID) Color.rgb(20, 120, 216) else Color.rgb(142, 142, 142)
        canvas.drawRect(leftTabRect, fillPaint)

        fillPaint.color = if (brushTopTab == BrushModalTopTab.SETTINGS_SLIDERS) Color.rgb(20, 120, 216) else Color.rgb(142, 142, 142)
        canvas.drawRect(rightTabRect, fillPaint)

        drawLibraryTabIcon(canvas, leftTabRect.centerX(), leftTabRect.centerY(), 58f * sx)
        drawSlidersTabIcon(canvas, rightTabRect.centerX(), rightTabRect.centerY(), 58f * sx)

        hits.add(ModalHit("TAB_BRUSH_LIBRARY", leftTabRect))
        hits.add(ModalHit("TAB_BRUSH_SETTINGS", rightTabRect))

        val prevTop = top + topTabH
        val prevH = 166f * sy
        val prevRect = RectF(left, prevTop, right, prevTop + prevH)
        fillPaint.color = Color.WHITE
        canvas.drawRect(prevRect, fillPaint)

        val activeBrush = selectedBrush()
        val activeSet = selectedSet()
        drawContainedBrushPreview(canvas, prevRect, activeBrush, sx)

        val infoTop = prevRect.bottom
        val infoH = 158f * sy
        val infoBot = infoTop + infoH
        val infoRect = RectF(left, infoTop, right, infoBot)
        fillPaint.color = Color.rgb(240, 240, 240)
        canvas.drawRect(infoRect, fillPaint)

        val iconRect = RectF(left + 28f * sx, infoTop + 16f * sy, left + 124f * sx, infoBot - 14f * sy)
        drawRealisticBrushIcon(canvas, iconRect, activeBrush.iconStyle, sx)

        textPaint.color = Color.rgb(90, 90, 90)
        textPaint.textSize = 42f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        val maxInfoTextW = (right - left) - 275f * sx
        canvas.drawText(ellipsizeText(activeBrush.name, textPaint, maxInfoTextW), left + 150f * sx, infoTop + 76f * sy, textPaint)

        subTextPaint.color = Color.rgb(118, 118, 118)
        subTextPaint.textSize = 31f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(ellipsizeText(activeSet.name, subTextPaint, maxInfoTextW), left + 150f * sx, infoTop + 128f * sy, subTextPaint)

        if (brushTopTab == BrushModalTopTab.SETTINGS_SLIDERS || subOverlay == BrushSubOverlay.SET_SETTINGS || subOverlay == BrushSubOverlay.BRUSH_ACTIONS) {
            val listBtnRect = RectF(right - 120f * sx, infoTop + 26f * sy, right - 24f * sx, infoBot - 26f * sy)
            SketchIcons.drawTopIcon1Menu(canvas, listBtnRect.centerX(), listBtnRect.centerY(), 125f * sx, Color.rgb(95, 95, 95))
            hits.add(ModalHit("BTN_PINNED_BRUSH_ACTIONS", listBtnRect))
        }

        val bodyTop = infoBot
        when {
            subOverlay == BrushSubOverlay.SET_SETTINGS -> drawSetSettingsOverlay(canvas, left, bodyTop, right, bottom, sx, sy)
            subOverlay == BrushSubOverlay.BRUSH_ACTIONS -> drawBrushActionsOverlay(canvas, left, bodyTop, right, bottom, sx, sy)
            brushTopTab == BrushModalTopTab.LIBRARY_GRID -> drawScrollableBrushLibraryGrid(canvas, left, bodyTop, right, bottom, sx, sy)
            else -> drawBrushSettingsSlidersPanel(canvas, left, bodyTop, right, bottom, sx, sy, activeBrush)
        }
    }

    private fun ellipsizeText(str: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(str) <= maxW) return str
        var s = str
        while (s.length > 1 && paint.measureText("$s...") > maxW) {
            s = s.dropLast(1)
        }
        return "$s..."
    }

    private fun drawContainedBrushPreview(canvas: Canvas, rect: RectF, brush: SketchBrushItem, sx: Float) {
        canvas.save()
        canvas.clipRect(rect)

        if (colorState.isColorlessEraser || brush.brushType == SketchBrushType.ERASER) {
            val sq = 18f * sx
            var ry = rect.top
            var rowIdx = 0
            while (ry < rect.bottom) {
                var rx = rect.left
                var colIdx = rowIdx % 2
                while (rx < rect.right) {
                    fillPaint.color = if (colIdx % 2 == 0) Color.rgb(245, 245, 245) else Color.rgb(222, 222, 222)
                    canvas.drawRect(rx, ry, min(rect.right, rx + sq), min(rect.bottom, ry + sq), fillPaint)
                    rx += sq
                    colIdx++
                }
                ry += sq
                rowIdx++
            }
        }

        val padX = 48f * sx
        val startX = rect.left + padX
        val endX = rect.right - padX
        val spanW = (endX - startX).coerceAtLeast(10f)
        val cy = rect.centerY()
        val amp = rect.height() * 0.16f

        val drawColor = if (colorState.isColorlessEraser || brush.brushType == SketchBrushType.ERASER) {
            Color.rgb(190, 195, 205)
        } else {
            colorState.currentColor
        }

        val previewBrush = brush.copy(size = (brush.size * 0.55f * sx).coerceIn(2f * sx, rect.height() * 0.78f))
        val procHist = mutableListOf<PointF>()
        val totalSamples = 240
        var prevX = startX
        var prevY = cy + sin(Math.PI * 0.9).toFloat() * amp
        var carryDist = 0f
        var dabIdx = 0

        UnifiedBrushRenderer.renderSingleDab(
            canvas = canvas,
            brush = previewBrush,
            x = prevX,
            y = prevY,
            tangentDeg = 0f,
            pressureNorm = 0.15f,
            baseColor = drawColor,
            isTransparentColor = false,
            dabIndex = dabIdx++,
            deterministicRandom = true,
            procHistory = procHist,
            smudgeBitmap = null,
            smudgeCarryColorHolder = null
        )

        for (s in 1..totalSamples) {
            val t = s.toFloat() / totalSamples.toFloat()
            val curX = startX + t * spanW
            val curY = cy + sin(t * Math.PI * 2.0 + Math.PI * 0.9).toFloat() * amp
            val segLen = hypot(curX - prevX, curY - prevY)
            val tangentDeg = Math.toDegrees(atan2((curY - prevY).toDouble(), (curX - prevX).toDouble())).toFloat()

            val pNorm = sin(t * Math.PI).toFloat().coerceIn(0.10f, 1.0f)
            val sizeScale = (previewBrush.sizeLightPressure + (previewBrush.sizeHeavyPressure - previewBrush.sizeLightPressure) * pNorm) / 100f
            val curRad = (previewBrush.size * 0.5f * sizeScale.coerceIn(0.15f, 1.4f)).coerceAtLeast(1.2f)
            val stepSpacingPx = max(1.2f * sx, curRad * 2f * (previewBrush.spacing / 100f).coerceIn(0.03f, 2.5f))

            var distLeft = carryDist + segLen
            if (distLeft >= stepSpacingPx && segLen > 0.0001f) {
                var traveled = stepSpacingPx - carryDist
                while (traveled <= segLen) {
                    val frac = (traveled / segLen).coerceIn(0f, 1f)
                    val sxPt = prevX + (curX - prevX) * frac
                    val syPt = prevY + (curY - prevY) * frac
                    UnifiedBrushRenderer.renderSingleDab(
                        canvas = canvas,
                        brush = previewBrush,
                        x = sxPt,
                        y = syPt,
                        tangentDeg = tangentDeg,
                        pressureNorm = pNorm,
                        baseColor = drawColor,
                        isTransparentColor = false,
                        dabIndex = dabIdx++,
                        deterministicRandom = true,
                        procHistory = procHist,
                        smudgeBitmap = null,
                        smudgeCarryColorHolder = null
                    )
                    traveled += stepSpacingPx
                }
                carryDist = segLen - (traveled - stepSpacingPx)
            } else {
                carryDist = distLeft
            }
            prevX = curX
            prevY = curY
        }

        canvas.restore()
    }

    private fun drawScrollableBrushLibraryGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val panelW = right - left
        fillPaint.color = Color.rgb(240, 240, 240)
        libraryScrollBounds.set(left, top, right, bottom)
        canvas.drawRect(libraryScrollBounds, fillPaint)

        val setHeaderH = 92f * sy
        val cellW = (panelW - 40f * sx) / 5f
        val cellH = 148f * sy

        var totalContentH = 24f * sy
        for (set in brushSets) {
            val rows = (set.brushes.size + 4) / 5
            totalContentH += setHeaderH + rows * cellH + 28f * sy
        }
        val maxScroll = max(0f, totalContentH - libraryScrollBounds.height())
        libraryScrollY = libraryScrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(libraryScrollBounds)

        var curY = top + 12f * sy - libraryScrollY
        for ((sIdx, set) in brushSets.withIndex()) {
            val isThisSetSelected = (sIdx == selectedSetIndex)

            if (curY + setHeaderH >= top && curY <= bottom) {
                textPaint.color = Color.rgb(92, 92, 92)
                textPaint.textSize = 37f * sx
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.isFakeBoldText = false
                canvas.drawText(ellipsizeText(set.name, textPaint, (right - left) - 220f * sx), left + 28f * sx, curY + 58f * sy, textPaint)

                val pinCx = if (isThisSetSelected) right - 154f * sx else right - 58f * sx
                val pinCy = curY + 46f * sy
                drawPinCircleIcon(canvas, pinCx, pinCy, 27f * sx, active = set.isPinned)
                hits.add(ModalHit("SET_TOGGLE_PIN", RectF(pinCx - 38f * sx, pinCy - 38f * sy, pinCx + 38f * sx, pinCy + 38f * sy), sIdx))

                if (isThisSetSelected) {
                    val menuCx = right - 58f * sx
                    SketchIcons.drawTopIcon1Menu(canvas, menuCx, pinCy, 110f * sx, Color.rgb(95, 95, 95))
                    hits.add(ModalHit("BTN_OPEN_SET_SETTINGS", RectF(menuCx - 48f * sx, pinCy - 42f * sy, menuCx + 48f * sx, pinCy + 42f * sy), sIdx))
                }
            }
            curY += setHeaderH

            val rows = (set.brushes.size + 4) / 5
            for (r in 0 until rows) {
                val rowTop = curY + r * cellH
                if (rowTop + cellH >= top && rowTop <= bottom) {
                    for (c in 0 until 5) {
                        val bIdx = r * 5 + c
                        if (bIdx >= set.brushes.size) break
                        val brush = set.brushes[bIdx]
                        val cellLeft = left + 20f * sx + c * cellW
                        val cellRect = RectF(cellLeft + 6f * sx, rowTop + 6f * sy, cellLeft + cellW - 6f * sx, rowTop + cellH - 6f * sy)

                        val isSelectedBrush = (sIdx == selectedSetIndex && bIdx == selectedBrushIndex)
                        if (isSelectedBrush) {
                            fillPaint.color = Color.WHITE
                            canvas.drawRoundRect(cellRect, 12f * sx, 12f * sx, fillPaint)
                            strokePaint.color = Color.rgb(42, 130, 215)
                            strokePaint.strokeWidth = 4.2f * sx
                            canvas.drawRoundRect(cellRect, 12f * sx, 12f * sx, strokePaint)
                        }

                        val iconBox = RectF(
                            cellRect.left + 16f * sx,
                            cellRect.top + 14f * sy,
                            cellRect.right - 16f * sx,
                            cellRect.bottom - 14f * sy
                        )
                        drawRealisticBrushIcon(canvas, iconBox, brush.iconStyle, sx)

                        val hitRect = RectF(cellRect.left, max(top, cellRect.top), cellRect.right, min(bottom, cellRect.bottom))
                        if (hitRect.height() > 15f * sy) {
                            hits.add(ModalHit("SELECT_BRUSH_CELL", hitRect, sIdx, bIdx))
                        }
                    }
                }
            }
            curY += rows * cellH + 28f * sy
        }

        drawCornerScrollArrows(canvas, right - 24f * sx, bottom - 24f * sy, 18f * sx)
        canvas.restore()
    }

    private fun drawSetSettingsOverlay(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, sx: Float, sy: Float) {
        fillPaint.color = Color.rgb(235, 235, 235)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        val rowH = 148f * sy
        val items = listOf(
            Pair("SET_ACT_RENAME", "Rename Set"),
            Pair("SET_ACT_CREATE_NEW", "Create New Set"),
            Pair("SET_ACT_DUPLICATE_BRUSH", "Duplicate Brush"),
            Pair("SET_ACT_DELETE_SET", "Delete Set")
        )

        for ((i, item) in items.withIndex()) {
            val ry = top + i * rowH
            val cy = ry + rowH * 0.5f
            val iconCx = left + 108f * sx

            when (item.first) {
                "SET_ACT_RENAME" -> drawRenameBoxIcon(canvas, iconCx, cy, 72f * sx)
                "SET_ACT_CREATE_NEW" -> drawCreateSetIcon(canvas, iconCx, cy, 62f * sx)
                "SET_ACT_DUPLICATE_BRUSH" -> drawDuplicateNibIcon(canvas, iconCx, cy, 60f * sx)
                "SET_ACT_DELETE_SET" -> SketchIcons.drawTrashIcon(canvas, iconCx, cy, 56f * sx, Color.rgb(92, 92, 92))
            }

            textPaint.color = Color.rgb(88, 88, 88)
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(item.second, left + 205f * sx, cy + 14f * sy, textPaint)

            strokePaint.color = Color.rgb(130, 130, 130)
            strokePaint.strokeWidth = 2f
            canvas.drawLine(left, ry + rowH, right, ry + rowH, strokePaint)

            hits.add(ModalHit(item.first, RectF(left, ry, right, ry + rowH)))
        }
    }

    private fun drawBrushActionsOverlay(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, sx: Float, sy: Float) {
        fillPaint.color = Color.rgb(235, 235, 235)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        val rowH = 148f * sy
        val items = listOf(
            Pair("BRUSH_ACT_RENAME", "Rename"),
            Pair("BRUSH_ACT_DUPLICATE", "Duplicate"),
            Pair("BRUSH_ACT_CHANGE_ICON", "Change Icon"),
            Pair("BRUSH_ACT_EXPORT_ICON", "Export Icon"),
            Pair("BRUSH_ACT_DELETE", "Delete")
        )

        for ((i, item) in items.withIndex()) {
            val ry = top + i * rowH
            val cy = ry + rowH * 0.5f
            val iconCx = left + 108f * sx

            when (item.first) {
                "BRUSH_ACT_RENAME" -> drawRenameBoxIcon(canvas, iconCx, cy, 72f * sx)
                "BRUSH_ACT_DUPLICATE" -> drawDuplicateNibIcon(canvas, iconCx, cy, 60f * sx)
                "BRUSH_ACT_CHANGE_ICON" -> drawChangeIconBox(canvas, iconCx, cy, 62f * sx)
                "BRUSH_ACT_EXPORT_ICON" -> drawExportIconBox(canvas, iconCx, cy, 62f * sx)
                "BRUSH_ACT_DELETE" -> SketchIcons.drawTrashIcon(canvas, iconCx, cy, 56f * sx, Color.rgb(92, 92, 92))
            }

            textPaint.color = Color.rgb(88, 88, 88)
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(item.second, left + 205f * sx, cy + 14f * sy, textPaint)

            if (i < items.size - 1) {
                strokePaint.color = Color.rgb(130, 130, 130)
                strokePaint.strokeWidth = 2f
                canvas.drawLine(left, ry + rowH, right, ry + rowH, strokePaint)
            }

            hits.add(ModalHit(item.first, RectF(left, ry, right, ry + rowH)))
        }
    }

    private fun drawBrushSettingsSlidersPanel(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float,
        brush: SketchBrushItem
    ) {
        val panelW = right - left
        fillPaint.color = Color.rgb(48, 48, 48)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        val typeRowH = 148f * sy
        val typeRowBot = top + typeRowH
        textPaint.color = Color.WHITE
        textPaint.textSize = 38f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Brush Types:", left + 40f * sx, top + 90f * sy, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(brush.brushType.title, right - 102f * sx, top + 90f * sy, textPaint)

        val chevX = right - 54f * sx
        val chevY = top + 76f * sy
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 4.5f * sx
        if (subOverlay == BrushSubOverlay.BRUSH_TYPES_DROPDOWN) {
            canvas.drawLine(chevX - 18f * sx, chevY - 8f * sy, chevX, chevY + 12f * sy, strokePaint)
            canvas.drawLine(chevX + 18f * sx, chevY - 8f * sy, chevX, chevY + 12f * sy, strokePaint)
        } else {
            canvas.drawLine(chevX - 8f * sx, chevY - 18f * sy, chevX + 12f * sx, chevY, strokePaint)
            canvas.drawLine(chevX - 8f * sx, chevY + 18f * sy, chevX + 12f * sx, chevY, strokePaint)
        }
        hits.add(ModalHit("TOGGLE_BRUSH_TYPES_DROPDOWN", RectF(left, top, right, typeRowBot)))

        val subTabH = 128f * sy
        val subTabBot = typeRowBot + subTabH
        val midX = left + panelW * 0.5f
        fillPaint.color = Color.rgb(41, 41, 41)
        canvas.drawRect(left, typeRowBot, right, subTabBot, fillPaint)
        strokePaint.color = Color.rgb(32, 32, 32)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(midX, typeRowBot + 16f * sy, midX, subTabBot - 16f * sy, strokePaint)

        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = if (settingsSubTab == BrushSettingsSubTab.BASIC) Color.rgb(65, 148, 238) else Color.rgb(115, 115, 115)
        canvas.drawText("BASIC", (left + midX) * 0.5f, typeRowBot + 78f * sy, textPaint)

        textPaint.color = if (settingsSubTab == BrushSettingsSubTab.ADVANCED) Color.rgb(65, 148, 238) else Color.rgb(115, 115, 115)
        canvas.drawText("ADVANCED", (midX + right) * 0.5f, typeRowBot + 78f * sy, textPaint)

        hits.add(ModalHit("SUBTAB_BASIC", RectF(left, typeRowBot, midX, subTabBot)))
        hits.add(ModalHit("SUBTAB_ADVANCED", RectF(midX, typeRowBot, right, subTabBot)))

        val resetH = 130f * sy
        val resetTop = bottom - resetH
        val contentBottom = resetTop

        if (settingsSubTab == BrushSettingsSubTab.BASIC) {
            var syCur = subTabBot + 36f * sy
            syCur = drawDarkSettingsSlider(
                canvas, left, right, syCur, sx, sy,
                id = "SLIDER_BASIC_SIZE",
                label = "Size",
                valueStr = String.format("%.1f", brush.size),
                fraction = ((brush.size - 1f) / 299f).coerceIn(0f, 1f)
            )
            strokePaint.color = Color.rgb(38, 38, 38)
            canvas.drawLine(left, syCur, right, syCur, strokePaint)
            syCur += 28f * sy
            drawDarkSettingsSlider(
                canvas, left, right, syCur, sx, sy,
                id = "SLIDER_BASIC_OPACITY",
                label = "Opacity",
                valueStr = "${brush.opacity.roundToInt()}%",
                fraction = (brush.opacity / 100f).coerceIn(0f, 1f)
            )
        } else {
            drawAdvancedAccordionSections(canvas, left, subTabBot, right, contentBottom, sx, sy, brush)
        }

        fillPaint.color = Color.rgb(48, 48, 48)
        canvas.drawRect(left, resetTop, right, bottom, fillPaint)
        strokePaint.color = Color.rgb(190, 190, 190)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(left, resetTop, right, resetTop, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 38f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Reset", (left + right) * 0.5f, resetTop + 80f * sy, textPaint)
        hits.add(ModalHit("BTN_RESET_BRUSH", RectF(left, resetTop, right, bottom)))

        if (subOverlay == BrushSubOverlay.BRUSH_TYPES_DROPDOWN) {
            drawBrushTypesDropdownOverlay(canvas, left + 22f * sx, subTabBot - 18f * sy, right - 22f * sx, bottom, sx, sy, brush)
        }
    }

    private fun drawAdvancedAccordionSections(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float,
        brush: SketchBrushItem
    ) {
        advancedScrollBounds.set(left, top, right, bottom)
        val sections = listOf("Pressure", "Stamp", "Nib", "Randomness")
        val rowH = 128f * sy

        canvas.save()
        canvas.clipRect(advancedScrollBounds)

        var curY = top - advancedScrollY
        for ((idx, title) in sections.withIndex()) {
            val rRect = RectF(left, curY, right, curY + rowH)
            fillPaint.color = Color.rgb(48, 48, 48)
            canvas.drawRect(rRect, fillPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 38f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(title, left + 40f * sx, curY + 78f * sy, textPaint)

            val chevX = right - 54f * sx
            val chevY = curY + 64f * sy
            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = 4.5f * sx
            if (expandedAdvancedSection == idx) {
                canvas.drawLine(chevX - 18f * sx, chevY - 8f * sy, chevX, chevY + 12f * sy, strokePaint)
                canvas.drawLine(chevX + 18f * sx, chevY - 8f * sy, chevX, chevY + 12f * sy, strokePaint)
            } else {
                canvas.drawLine(chevX - 8f * sx, chevY - 18f * sy, chevX + 12f * sx, chevY, strokePaint)
                canvas.drawLine(chevX - 8f * sx, chevY + 18f * sy, chevX + 12f * sx, chevY, strokePaint)
            }

            strokePaint.color = Color.rgb(38, 38, 38)
            strokePaint.strokeWidth = 2f
            canvas.drawLine(left, curY + rowH, right, curY + rowH, strokePaint)

            val clippedHit = RectF(left, max(top, curY), right, min(bottom, curY + rowH))
            if (clippedHit.height() > 20f * sy) {
                hits.add(ModalHit("TOGGLE_ADV_SECTION", clippedHit, idx))
            }
            curY += rowH

            if (expandedAdvancedSection == idx) {
                curY += 18f * sy
                when (idx) {
                    0 -> {
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_PRESS_SIZE_HEAVY", "Size Heavy Pressure", "${brush.sizeHeavyPressure.roundToInt()}%", brush.sizeHeavyPressure / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_PRESS_SIZE_LIGHT", "Size Light Pressure", "${brush.sizeLightPressure.roundToInt()}%", brush.sizeLightPressure / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_PRESS_OPAC_LIGHT", "Min Opacity Pressure", "${brush.opacityLightPressure.roundToInt()}%", brush.opacityLightPressure / 100f)
                    }
                    1 -> {
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_STAMP_SPACING", "Spacing", "${brush.spacing.roundToInt()}%", (brush.spacing - 2f) / 98f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_STAMP_ROUND", "Roundness", "${brush.roundness.roundToInt()}%", (brush.roundness - 10f) / 90f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_STAMP_ROT", "Rotation", "${brush.rotation.roundToInt()}°", (brush.rotation + 180f) / 360f)
                    }
                    2 -> {
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_NIB_HARDNESS", "Hardness", "${brush.hardness.roundToInt()}%", brush.hardness / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_NIB_GRAIN", "Texture Grain", "${brush.grainStrength.roundToInt()}%", brush.grainStrength / 100f)
                    }
                    3 -> {
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_RAND_SIZE", "Size Jitter", "${brush.sizeJitter.roundToInt()}%", brush.sizeJitter / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_RAND_OPAC", "Opacity Jitter", "${brush.opacityJitter.roundToInt()}%", brush.opacityJitter / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_RAND_SCATTER", "Scatter", "${brush.scatterJitter.roundToInt()}%", brush.scatterJitter / 100f)
                    }
                }
            }
        }
        canvas.restore()
    }

    private fun drawDarkSettingsSlider(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        sx: Float,
        sy: Float,
        id: String,
        label: String,
        valueStr: String,
        fraction: Float
    ): Float {
        val f = fraction.coerceIn(0f, 1f)
        textPaint.color = Color.WHITE
        textPaint.textSize = 35f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, left + 40f * sx, top + 44f * sy, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(valueStr, right - 40f * sx, top + 44f * sy, textPaint)

        val trackLeft = left + 40f * sx
        val trackRight = right - 40f * sx
        val trackY = top + 114f * sy
        val thumbX = trackLeft + (trackRight - trackLeft) * f

        strokePaint.color = Color.rgb(168, 168, 168)
        strokePaint.strokeWidth = 4.2f * sx
        canvas.drawLine(thumbX, trackY, trackRight, trackY, strokePaint)

        strokePaint.color = Color.rgb(80, 154, 227)
        strokePaint.strokeWidth = 5.2f * sx
        canvas.drawLine(trackLeft, trackY, thumbX, trackY, strokePaint)

        val knobR = 26f * sx
        fillPaint.color = Color.WHITE
        canvas.drawCircle(thumbX, trackY, knobR, fillPaint)

        val hitRect = RectF(trackLeft - 30f * sx, trackY - 46f * sy, trackRight + 30f * sx, trackY + 46f * sy)
        sliderRects[id] = hitRect
        return top + 178f * sy
    }

    private fun drawBrushTypesDropdownOverlay(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float,
        brush: SketchBrushItem
    ) {
        val types = SketchBrushType.values()
        val rowH = 132f * sy
        val boxBot = min(bottom - 16f * sy, top + types.size * rowH + 24f * sy)
        val dropRect = RectF(left, top, right, boxBot)

        fillPaint.color = Color.rgb(39, 39, 39)
        canvas.drawRoundRect(dropRect, 14f * sx, 14f * sx, fillPaint)

        for ((i, t) in types.withIndex()) {
            val ry = top + 12f * sy + i * rowH
            val isSel = (brush.brushType == t)
            textPaint.color = if (isSel) Color.rgb(65, 148, 238) else Color.WHITE
            textPaint.textSize = 38f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(t.title, left + 42f * sx, ry + 80f * sy, textPaint)

            if (isSel) {
                strokePaint.color = Color.rgb(55, 140, 235)
                strokePaint.strokeWidth = 5f * sx
                val cx = right - 95f * sx
                val cy = ry + 66f * sy
                canvas.drawLine(cx - 18f * sx, cy, cx - 4f * sx, cy + 14f * sy, strokePaint)
                canvas.drawLine(cx - 4f * sx, cy + 14f * sy, cx + 24f * sx, cy - 14f * sy, strokePaint)
            }

            hits.add(ModalHit("SELECT_BRUSH_TYPE", RectF(left, ry, right, ry + rowH), i))
        }
    }

    // ==========================================================================
    // 1-TO-1 SKETCHBOOK COLOR MENU
    // ==========================================================================
    private fun drawColorModalContent(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float,
        bgMode: Boolean
    ) {
        val panelW = right - left
        val topTabH = 132f * sy

        val tabW = panelW / 3f
        for (i in 0..2) {
            val tLeft = left + i * tabW
            val tRect = RectF(tLeft, top, tLeft + tabW, top + topTabH)
            fillPaint.color = if (colorState.topTabIndex == i) Color.rgb(20, 120, 216) else Color.rgb(142, 142, 142)
            canvas.drawRect(tRect, fillPaint)

            when (i) {
                0 -> SketchIcons.drawTopIcon6ColorWheel(canvas, tRect.centerX(), tRect.centerY(), topTabH * 0.95f)
                1 -> SketchIcons.drawTopIcon4Grid(canvas, tRect.centerX(), tRect.centerY(), topTabH * 0.95f, Color.rgb(225, 225, 225))
                2 -> SketchIcons.drawPictureSaveIcon(canvas, tRect.centerX(), tRect.centerY(), 54f * sx, isCopy = false, color = Color.rgb(225, 225, 225), bgMask = Color.rgb(142, 142, 142))
            }
            hits.add(ModalHit("COLOR_TOP_TAB", tRect, i))
        }

        val bodyTop = top + topTabH
        if (colorState.topTabIndex == 1) {
            fillPaint.color = Color.rgb(52, 52, 52)
            canvas.drawRect(left, bodyTop, right, bottom, fillPaint)
            drawFullCopicSwatchGridTab(canvas, left, bodyTop, right, bottom, sx, sy)
            return
        }

        drawColorWheelBody(canvas, left, bodyTop, right, bottom, sx, sy, state = colorState, isBgMode = bgMode)
    }

    private fun drawColorWheelBody(
        canvas: Canvas,
        left: Float,
        bodyTop: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float,
        state: ColorStudioState,
        isBgMode: Boolean
    ) {
        val panelW = right - left
        fillPaint.color = Color.rgb(52, 52, 52)
        canvas.drawRect(left, bodyTop, right, bottom, fillPaint)

        val swatchBox = RectF(left + 28f * sx, bodyTop + 48f * sy, left + 220f * sx, bodyTop + 236f * sy)
        val halfSwW = swatchBox.width() * 0.5f
        fillPaint.color = state.previousColor
        canvas.drawRoundRect(RectF(swatchBox.left, swatchBox.top, swatchBox.left + halfSwW + 6f * sx, swatchBox.bottom), 8f * sx, 8f * sx, fillPaint)
        fillPaint.color = state.currentColor
        canvas.drawRoundRect(RectF(swatchBox.left + halfSwW, swatchBox.top, swatchBox.right, swatchBox.bottom), 8f * sx, 8f * sx, fillPaint)
        hits.add(ModalHit("REVERT_PREV_COLOR", RectF(swatchBox.left, swatchBox.top, swatchBox.left + halfSwW, swatchBox.bottom)))

        val stripLeft = left + 246f * sx
        val stripRight = right - 168f * sx
        subTextPaint.color = Color.WHITE
        subTextPaint.textSize = 27f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Complementary", stripLeft, bodyTop + 62f * sy, subTextPaint)

        val compColors = state.computeHarmonyStrip()
        val compTop = bodyTop + 82f * sy
        val compBot = bodyTop + 128f * sy
        val compChipW = (stripRight - stripLeft) / compColors.size
        for ((idx, cc) in compColors.withIndex()) {
            val cRect = RectF(stripLeft + idx * compChipW, compTop, stripLeft + (idx + 1) * compChipW, compBot)
            fillPaint.color = cc
            canvas.drawRect(cRect, fillPaint)
            hits.add(ModalHit("PICK_COMP_COLOR", cRect, cc))
        }

        val harmCx = right - 88f * sx
        val harmCy = (compTop + compBot) * 0.5f
        drawHarmonyRingsIcon(canvas, harmCx, harmCy, 24f * sx)
        hits.add(ModalHit("CYCLE_HARMONY_MODE", RectF(harmCx - 42f * sx, harmCy - 42f * sy, harmCx + 42f * sx, harmCy + 42f * sy)))

        canvas.drawText("Color History", stripLeft, bodyTop + 168f * sy, subTextPaint)
        val histTop = bodyTop + 188f * sy
        val histBot = bodyTop + 234f * sy
        val histChipW = (stripRight - stripLeft) / 9f
        for (i in 0 until min(9, state.colorHistory.size)) {
            val hc = state.colorHistory[i]
            val hRect = RectF(stripLeft + i * histChipW, histTop, stripLeft + (i + 1) * histChipW, histBot)
            fillPaint.color = hc
            canvas.drawRect(hRect, fillPaint)
            hits.add(ModalHit("PICK_HIST_COLOR", hRect, hc))
        }

        val barCx = right - 88f * sx
        val barCy = (histTop + histBot) * 0.5f
        drawBarcodeIcon(canvas, barCx, barCy, 22f * sx, Color.WHITE)
        hits.add(ModalHit("SHUFFLE_HISTORY_BAR", RectF(barCx - 42f * sx, barCy - 42f * sy, barCx + 42f * sx, barCy + 42f * sy)))

        wheelCx = (left + right) * 0.5f
        wheelCy = bodyTop + 668f * sy
        wheelOuterR = 324f * sx
        wheelInnerR = 222f * sx
        diamondHalfDiag = 192f * sx

        // Only show Transparent Colorless Eraser puck in Brush Color mode (not in Background Color mode matching 164119.jpg!)
        if (!isBgMode) {
            val transCx = left + 78f * sx
            val transCy = bodyTop + 382f * sy
            drawTransparentEraserButton(canvas, transCx, transCy, 44f * sx, state.isColorlessEraser)
            hits.add(ModalHit("TOGGLE_COLORLESS_ERASER", RectF(transCx - 52f * sx, transCy - 52f * sy, transCx + 52f * sx, transCy + 52f * sy)))
        }

        val dropCx = right - 78f * sx
        val dropCy = bodyTop + 382f * sy
        drawEyedropperIcon(canvas, dropCx, dropCy, 42f * sx)
        hits.add(ModalHit("ACTIVATE_EYEDROPPER", RectF(dropCx - 52f * sx, dropCy - 52f * sy, dropCx + 52f * sx, dropCy + 52f * sy)))

        fillPaint.color = Color.rgb(20, 20, 20)
        canvas.drawCircle(wheelCx, wheelCy, wheelOuterR + 12f * sx, fillPaint)

        val ringRadius = (wheelOuterR + wheelInnerR) * 0.5f
        val ringThickness = wheelOuterR - wheelInnerR
        val hueSweepColors = intArrayOf(
            Color.RED,
            Color.MAGENTA,
            Color.BLUE,
            Color.CYAN,
            Color.GREEN,
            Color.YELLOW,
            Color.rgb(255, 128, 0),
            Color.RED
        )
        strokePaint.shader = SweepGradient(wheelCx, wheelCy, hueSweepColors, null)
        strokePaint.strokeWidth = ringThickness
        canvas.drawCircle(wheelCx, wheelCy, ringRadius, strokePaint)
        strokePaint.shader = null

        drawRotatedSlDiamond(canvas, wheelCx, wheelCy, diamondHalfDiag, state.hue)

        val hueAngleRad = hueToWheelAngleRad(state.hue)
        val hThumbX = wheelCx + cos(hueAngleRad).toFloat() * ringRadius
        val hThumbY = wheelCy + sin(hueAngleRad).toFloat() * ringRadius
        val pureHueColor = ColorStudioState.hslToColor(state.hue, 1f, 0.5f)
        drawSelectorRing(canvas, hThumbX, hThumbY, 27f * sx, pureHueColor)

        val dPt = slToDiamondPoint(state.saturation, state.lightness, wheelCx, wheelCy, diamondHalfDiag)
        drawSelectorRing(canvas, dPt.x, dPt.y, 27f * sx, state.currentColor)

        subTextPaint.color = Color.rgb(138, 138, 138)
        subTextPaint.textSize = 33f * sx
        subTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("HEX #", right - 26f * sx, bodyTop + 926f * sy, subTextPaint)

        val hexStr = String.format("%06X", 0xFFFFFF and state.currentColor)
        textPaint.color = Color.rgb(225, 225, 225)
        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(hexStr, right - 26f * sx, bodyTop + 984f * sy, textPaint)
        hits.add(ModalHit("EDIT_HEX_COLOR", RectF(right - 210f * sx, bodyTop + 885f * sy, right - 12f * sx, bodyTop + 1005f * sy)))

        val modeY = bodyTop + 1095f * sy
        val modeCount = if (isBgMode) 4 else 5
        val modeSlotW = panelW / modeCount
        for (m in 0 until modeCount) {
            val mx = left + (m + 0.5f) * modeSlotW
            val mRect = RectF(left + m * modeSlotW, modeY - 45f * sy, left + (m + 1) * modeSlotW, modeY + 45f * sy)
            val isAct = (state.bottomModeIndex == m)
            val tint = if (isAct) Color.rgb(225, 225, 225) else Color.rgb(112, 112, 112)
            when (m) {
                0 -> {
                    textPaint.color = tint
                    textPaint.textSize = 38f * sx
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("HSL", mx, modeY + 14f * sy, textPaint)
                    textPaint.isFakeBoldText = false
                }
                1 -> {
                    textPaint.color = tint
                    textPaint.textSize = 38f * sx
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("RGB", mx, modeY + 14f * sy, textPaint)
                    textPaint.isFakeBoldText = false
                }
                2 -> drawSmall3x2GridIcon(canvas, mx, modeY, 34f * sx, tint)
                3 -> if (isBgMode) drawBarcodeIcon(canvas, mx, modeY, 30f * sx, tint) else drawShuffleArrowsIcon(canvas, mx, modeY, 34f * sx, tint)
                4 -> drawBarcodeIcon(canvas, mx, modeY, 30f * sx, tint)
            }
            hits.add(ModalHit("COLOR_BOTTOM_MODE", mRect, m))
        }

        val slidersTop = bodyTop + 1190f * sy
        if (state.bottomModeIndex == 1) {
            val r = Color.red(state.currentColor)
            val g = Color.green(state.currentColor)
            val b = Color.blue(state.currentColor)
            drawColorChannelSlider(canvas, left, right, slidersTop, sx, sy, "COLOR_R", "R", r.toString(), r / 255f, Color.BLACK, Color.rgb(255, g, b))
            drawColorChannelSlider(canvas, left, right, slidersTop + 132f * sy, sx, sy, "COLOR_G", "G", g.toString(), g / 255f, Color.BLACK, Color.rgb(r, 255, b))
            drawColorChannelSlider(canvas, left, right, slidersTop + 264f * sy, sx, sy, "COLOR_B", "B", b.toString(), b / 255f, Color.BLACK, Color.rgb(r, g, 255))
        } else {
            val hInt = state.hue.roundToInt()
            val sInt = (state.saturation * 100f).roundToInt()
            val lInt = (state.lightness * 100f).roundToInt()
            val effL = state.lightness.coerceIn(0.08f, 0.92f)
            val hSpectrum = intArrayOf(
                ColorStudioState.hslToColor(0f, 1f, effL),
                ColorStudioState.hslToColor(60f, 1f, effL),
                ColorStudioState.hslToColor(120f, 1f, effL),
                ColorStudioState.hslToColor(180f, 1f, effL),
                ColorStudioState.hslToColor(240f, 1f, effL),
                ColorStudioState.hslToColor(300f, 1f, effL),
                ColorStudioState.hslToColor(360f, 1f, effL)
            )
            val sStart = ColorStudioState.hslToColor(state.hue, 0f, effL)
            val sEnd = ColorStudioState.hslToColor(state.hue, 1f, effL)
            val lMid = ColorStudioState.hslToColor(state.hue, state.saturation, 0.5f)
            drawMultiStopColorSlider(canvas, left, right, slidersTop, sx, sy, "COLOR_H", "H", hInt.toString(), state.hue / 360f, hSpectrum)
            drawMultiStopColorSlider(canvas, left, right, slidersTop + 132f * sy, sx, sy, "COLOR_S", "S", sInt.toString(), state.saturation, intArrayOf(sStart, sEnd))
            drawMultiStopColorSlider(canvas, left, right, slidersTop + 264f * sy, sx, sy, "COLOR_L", "L", lInt.toString(), state.lightness, intArrayOf(Color.BLACK, lMid, Color.WHITE))
        }
    }

    private fun drawMultiStopColorSlider(
        canvas: Canvas,
        left: Float,
        right: Float,
        yCenter: Float,
        sx: Float,
        sy: Float,
        id: String,
        label: String,
        valStr: String,
        fraction: Float,
        stops: IntArray
    ) {
        val f = fraction.coerceIn(0f, 1f)
        subTextPaint.color = Color.rgb(155, 155, 155)
        subTextPaint.textSize = 33f * sx
        subTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, left + 90f * sx, yCenter + 12f * sy, subTextPaint)

        val trackLeft = left + 148f * sx
        val trackRight = right - 148f * sx
        val trackH = 26f * sy
        val trackRect = RectF(trackLeft, yCenter - trackH * 0.5f, trackRight, yCenter + trackH * 0.5f)

        fillPaint.shader = LinearGradient(trackLeft, yCenter, trackRight, yCenter, stops, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(trackRect, trackH * 0.5f, trackH * 0.5f, fillPaint)
        fillPaint.shader = null

        val knobX = trackLeft + (trackRight - trackLeft) * f
        fillPaint.color = Color.WHITE
        canvas.drawCircle(knobX, yCenter, 26f * sx, fillPaint)

        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(valStr, right - 112f * sx, yCenter + 12f * sy, subTextPaint)

        sliderRects[id] = RectF(trackLeft - 30f * sx, yCenter - 48f * sy, trackRight + 30f * sx, yCenter + 48f * sy)
    }

    private fun drawColorChannelSlider(
        canvas: Canvas,
        left: Float,
        right: Float,
        yCenter: Float,
        sx: Float,
        sy: Float,
        id: String,
        label: String,
        valStr: String,
        fraction: Float,
        gradStart: Int,
        gradEnd: Int
    ) {
        val f = fraction.coerceIn(0f, 1f)
        subTextPaint.color = Color.rgb(155, 155, 155)
        subTextPaint.textSize = 33f * sx
        subTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, left + 90f * sx, yCenter + 12f * sy, subTextPaint)

        val trackLeft = left + 148f * sx
        val trackRight = right - 148f * sx
        val trackH = 26f * sy
        val trackRect = RectF(trackLeft, yCenter - trackH * 0.5f, trackRight, yCenter + trackH * 0.5f)

        fillPaint.shader = LinearGradient(trackLeft, yCenter, trackRight, yCenter, gradStart, gradEnd, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(trackRect, trackH * 0.5f, trackH * 0.5f, fillPaint)
        fillPaint.shader = null

        val knobX = trackLeft + (trackRight - trackLeft) * f
        fillPaint.color = Color.WHITE
        canvas.drawCircle(knobX, yCenter, 26f * sx, fillPaint)

        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(valStr, right - 112f * sx, yCenter + 12f * sy, subTextPaint)

        sliderRects[id] = RectF(trackLeft - 30f * sx, yCenter - 48f * sy, trackRight + 30f * sx, yCenter + 48f * sy)
    }

    private fun drawRotatedSlDiamond(canvas: Canvas, cx: Float, cy: Float, halfDiag: Float, hue: Float) {
        val p = Path().apply {
            moveTo(cx, cy - halfDiag)
            lineTo(cx + halfDiag, cy)
            lineTo(cx, cy + halfDiag)
            lineTo(cx - halfDiag, cy)
            close()
        }
        canvas.save()
        canvas.clipPath(p)

        val pureHue = ColorStudioState.hslToColor(hue, 1f, 0.5f)
        fillPaint.shader = LinearGradient(
            cx - halfDiag, cy, cx + halfDiag, cy,
            Color.rgb(128, 128, 128), pureHue, Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - halfDiag, cy - halfDiag, cx + halfDiag, cy + halfDiag, fillPaint)

        fillPaint.shader = LinearGradient(
            cx, cy - halfDiag, cx, cy + halfDiag,
            intArrayOf(Color.WHITE, Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - halfDiag, cy - halfDiag, cx + halfDiag, cy + halfDiag, fillPaint)
        fillPaint.shader = null
        canvas.restore()
    }

    private fun drawFullCopicSwatchGridTab(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, sx: Float, sy: Float) {
        val cols = 6
        val rows = 10
        val pad = 24f * sx
        val cellW = (right - left - pad * 2f) / cols
        val cellH = (bottom - top - pad * 2f) / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val h = ((c * 60f + r * 12f) % 360f)
                val s = 0.75f
                val l = (0.18f + (r / 10f) * 0.72f).coerceIn(0.1f, 0.92f)
                val col = ColorStudioState.hslToColor(h, s, l)
                val rect = RectF(
                    left + pad + c * cellW + 4f * sx,
                    top + pad + r * cellH + 4f * sy,
                    left + pad + (c + 1) * cellW - 4f * sx,
                    top + pad + (r + 1) * cellH - 4f * sy
                )
                fillPaint.color = col
                canvas.drawRect(rect, fillPaint)
                hits.add(ModalHit("PICK_HIST_COLOR", rect, col))
            }
        }
    }

    private fun drawBottomThreeCircles(canvas: Canvas, viewW: Float, viewH: Float, sx: Float, sy: Float, isColorMode: Boolean) {
        val cy = viewH - 128f * sy
        val r = 61f * sx

        val leftCx = 291f * sx
        fillPaint.color = if (isColorMode) Color.rgb(69, 149, 230) else Color.rgb(33, 33, 33)
        canvas.drawCircle(leftCx, cy, r, fillPaint)
        SketchIcons.drawTopIcon6ColorWheel(canvas, leftCx, cy, r * 2.1f)
        hits.add(ModalHit("BOTTOM_CIRCLE_COLOR", RectF(leftCx - r, cy - r, leftCx + r, cy + r)))

        val midCx = 539f * sx
        fillPaint.color = Color.rgb(33, 33, 33)
        canvas.drawCircle(midCx, cy, r, fillPaint)
        strokePaint.color = Color.rgb(69, 149, 230)
        strokePaint.strokeWidth = 5f * sx
        if (subOverlay != BrushSubOverlay.NONE) {
            canvas.drawLine(midCx - 20f * sx, cy + 8f * sy, midCx, cy - 12f * sy, strokePaint)
            canvas.drawLine(midCx + 20f * sx, cy + 8f * sy, midCx, cy - 12f * sy, strokePaint)
        } else {
            val d = 19f * sx
            canvas.drawLine(midCx - d, cy - d, midCx + d, cy + d, strokePaint)
            canvas.drawLine(midCx + d, cy - d, midCx - d, cy + d, strokePaint)
        }
        hits.add(ModalHit("BOTTOM_CIRCLE_CLOSE", RectF(midCx - r, cy - r, midCx + r, cy + r)))

        val rightCx = 787f * sx
        fillPaint.color = if (!isColorMode) Color.rgb(69, 149, 230) else Color.rgb(33, 33, 33)
        canvas.drawCircle(rightCx, cy, r, fillPaint)
        drawBottomBrushSliderCircleIcon(canvas, rightCx, cy, r * 0.55f, isColorMode)
        hits.add(ModalHit("BOTTOM_CIRCLE_BRUSH", RectF(rightCx - r, cy - r, rightCx + r, cy + r)))
    }

    fun onTouchEvent(event: android.view.MotionEvent, viewW: Float, viewH: Float, isColorMode: Boolean, isBgColorMode: Boolean = false): Boolean {
        val x = event.x
        val y = event.y
        val sx = viewW / 1080f
        activeBgModeInTouch = isBgColorMode
        val activeColState = if (isBgColorMode) bgColorState else colorState

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastY = y
                draggingSliderId = null
                isDraggingHueRing = false
                isDraggingSlDiamond = false
                isScrollingLibrary = false
                isScrollingAdvanced = false

                for ((id, rect) in sliderRects) {
                    if (rect.contains(x, y)) {
                        draggingSliderId = id
                        updateSliderValue(id, x)
                        hostView.invalidate()
                        return true
                    }
                }

                if ((isColorMode || isBgColorMode) && activeColState.topTabIndex == 0) {
                    val dist = hypot(x - wheelCx, y - wheelCy)
                    if (dist in (wheelInnerR - 18f * sx)..(wheelOuterR + 24f * sx)) {
                        isDraggingHueRing = true
                        updateHueFromTouch(x, y, activeColState)
                        hostView.invalidate()
                        return true
                    }
                    val manhattan = abs(x - wheelCx) + abs(y - wheelCy)
                    if (manhattan <= diamondHalfDiag + 16f * sx) {
                        isDraggingSlDiamond = true
                        updateSlFromDiamondTouch(x, y, activeColState)
                        hostView.invalidate()
                        return true
                    }
                }
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = y - lastY
                lastY = y

                if (draggingSliderId != null) {
                    updateSliderValue(draggingSliderId!!, x)
                    hostView.invalidate()
                    return true
                }
                if (isDraggingHueRing) {
                    updateHueFromTouch(x, y, activeColState)
                    hostView.invalidate()
                    return true
                }
                if (isDraggingSlDiamond) {
                    updateSlFromDiamondTouch(x, y, activeColState)
                    hostView.invalidate()
                    return true
                }

                if (abs(y - downY) > 15f * sx) {
                    if (!isColorMode && !isBgColorMode && subOverlay == BrushSubOverlay.NONE) {
                        if (brushTopTab == BrushModalTopTab.LIBRARY_GRID && libraryScrollBounds.contains(downX, downY)) {
                            isScrollingLibrary = true
                            libraryScrollY -= dy
                            hostView.invalidate()
                            return true
                        }
                        if (brushTopTab == BrushModalTopTab.SETTINGS_SLIDERS &&
                            settingsSubTab == BrushSettingsSubTab.ADVANCED &&
                            advancedScrollBounds.contains(downX, downY)
                        ) {
                            isScrollingAdvanced = true
                            advancedScrollY = (advancedScrollY - dy).coerceIn(0f, 800f * sx)
                            hostView.invalidate()
                            return true
                        }
                    }
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP -> {
                val wasDragging = (draggingSliderId != null || isDraggingHueRing || isDraggingSlDiamond || isScrollingLibrary || isScrollingAdvanced)
                if (isBgColorMode && (isDraggingHueRing || isDraggingSlDiamond || draggingSliderId?.startsWith("COLOR_") == true)) {
                    bgColorState.pushToHistory(bgColorState.currentColor)
                }
                draggingSliderId = null
                isDraggingHueRing = false
                isDraggingSlDiamond = false
                isScrollingLibrary = false
                isScrollingAdvanced = false

                if (!wasDragging && abs(x - downX) < 30f * sx && abs(y - downY) < 30f * sx) {
                    handleModalTap(x, y, isBgColorMode)
                }
                hostView.invalidate()
                return true
            }
        }
        return true
    }

    private fun applyLiveBackgroundColorChange() {
        if (activeBgModeInTouch) {
            hostView.project.backgroundColor = bgColorState.currentColor
            hostView.project.backgroundVisible = true
            hostView.project.isDirty = true
        }
    }

    private fun handleModalTap(x: Float, y: Float, isBgColorMode: Boolean) {
        var hit: ModalHit? = null
        for (i in hits.size - 1 downTo 0) {
            if (hits[i].rect.contains(x, y)) {
                hit = hits[i]
                break
            }
        }
        if (hit == null) return

        val brush = selectedBrush()
        val set = selectedSet()
        val activeColState = if (isBgColorMode) bgColorState else colorState

        when (hit.id) {
            "BOTTOM_CIRCLE_COLOR" -> {
                subOverlay = BrushSubOverlay.NONE
                hostView.popupState = TopPopupState.COLOR_MENU
            }
            "BOTTOM_CIRCLE_BRUSH" -> {
                subOverlay = BrushSubOverlay.NONE
                hostView.popupState = TopPopupState.BRUSH_MENU
            }
            "BOTTOM_CIRCLE_CLOSE" -> {
                if (subOverlay != BrushSubOverlay.NONE) {
                    subOverlay = BrushSubOverlay.NONE
                } else {
                    hostView.popupState = TopPopupState.NONE
                }
            }
            "BG_TOP_TAB" -> {
                bgColorState.topTabIndex = hit.arg1.coerceIn(0, 1)
            }
            "BG_STUDIO_SUBTAB" -> {
                hostView.project.backgroundConfig.studioSubTab = hit.arg1.coerceIn(0, 2)
            }
            "BG_SELECT_SURFACE" -> {
                hostView.project.backgroundConfig.surfacePreset = hit.arg1.coerceIn(0, 5)
                hostView.project.backgroundVisible = true
                hostView.project.isDirty = true
            }
            "BG_SELECT_GRID" -> {
                hostView.project.backgroundConfig.gridPreset = hit.arg1.coerceIn(0, 5)
                hostView.project.backgroundVisible = true
                hostView.project.isDirty = true
            }
            "BG_TOGGLE_GRID_CONTRAST" -> {
                val cfg = hostView.project.backgroundConfig
                cfg.invertGridContrast = !cfg.invertGridContrast
                hostView.project.isDirty = true
            }
            "BG_SELECT_LIGHTING" -> {
                hostView.project.backgroundConfig.lightingPreset = hit.arg1.coerceIn(0, 3)
                hostView.project.backgroundVisible = true
                hostView.project.isDirty = true
            }
            "BG_RESET_STUDIO" -> {
                hostView.project.backgroundConfig.reset()
                hostView.project.isDirty = true
                hostView.showBanner("Background Studio Reset")
            }
            "TAB_BRUSH_LIBRARY" -> {
                brushTopTab = BrushModalTopTab.LIBRARY_GRID
                subOverlay = BrushSubOverlay.NONE
            }
            "TAB_BRUSH_SETTINGS" -> {
                brushTopTab = BrushModalTopTab.SETTINGS_SLIDERS
                subOverlay = BrushSubOverlay.NONE
            }
            "SELECT_BRUSH_CELL" -> {
                selectedSetIndex = hit.arg1.coerceIn(0, brushSets.lastIndex)
                selectedBrushIndex = hit.arg2.coerceIn(0, brushSets[selectedSetIndex].brushes.lastIndex)
            }
            "SET_TOGGLE_PIN" -> {
                if (hit.arg1 in brushSets.indices) {
                    brushSets[hit.arg1].isPinned = !brushSets[hit.arg1].isPinned
                }
            }
            "BTN_OPEN_SET_SETTINGS" -> {
                subOverlay = if (subOverlay == BrushSubOverlay.SET_SETTINGS) BrushSubOverlay.NONE else BrushSubOverlay.SET_SETTINGS
            }
            "BTN_PINNED_BRUSH_ACTIONS" -> {
                subOverlay = when {
                    subOverlay == BrushSubOverlay.SET_SETTINGS -> BrushSubOverlay.NONE
                    brushTopTab == BrushModalTopTab.SETTINGS_SLIDERS -> {
                        if (subOverlay == BrushSubOverlay.BRUSH_ACTIONS) BrushSubOverlay.NONE else BrushSubOverlay.BRUSH_ACTIONS
                    }
                    else -> BrushSubOverlay.NONE
                }
            }
            "SET_ACT_RENAME" -> {
                hostView.showSimpleInputModal("Rename Set", set.name) { newName ->
                    if (newName.isNotBlank()) set.name = newName.trim()
                    subOverlay = BrushSubOverlay.NONE
                    hostView.invalidate()
                }
            }
            "SET_ACT_CREATE_NEW" -> {
                val newSet = SketchBrushSet(
                    id = "set_${System.currentTimeMillis()}",
                    name = "New Set ${brushSets.size + 1}",
                    brushes = mutableListOf(brush.copy(id = "b_${System.currentTimeMillis()}", name = brush.name))
                )
                val insertPos = (selectedSetIndex + 1).coerceIn(0, brushSets.size)
                brushSets.add(insertPos, newSet)
                selectedSetIndex = insertPos
                selectedBrushIndex = 0
                subOverlay = BrushSubOverlay.NONE
                hostView.showBanner("Created '${newSet.name}' below current set")
            }
            "SET_ACT_DUPLICATE_BRUSH" -> {
                val dup = brush.copy(id = "b_${System.currentTimeMillis()}", name = "Duplicate")
                val insertIdx = (selectedBrushIndex + 1).coerceIn(0, set.brushes.size)
                set.brushes.add(insertIdx, dup)
                selectedBrushIndex = insertIdx
                subOverlay = BrushSubOverlay.NONE
                hostView.showBanner("Duplicated brush in '${set.name}'")
            }
            "SET_ACT_DELETE_SET" -> {
                if (brushSets.size <= 1) {
                    hostView.showBanner("Cannot delete the last remaining brush set")
                } else {
                    hostView.showConfirmDeleteSetModal(set.name) {
                        brushSets.removeAt(selectedSetIndex)
                        selectedSetIndex = selectedSetIndex.coerceIn(0, brushSets.lastIndex)
                        selectedBrushIndex = 0
                        subOverlay = BrushSubOverlay.NONE
                        hostView.showBanner("Deleted set")
                        hostView.invalidate()
                    }
                }
            }
            "BRUSH_ACT_RENAME" -> {
                hostView.showSimpleInputModal("Rename Brush", brush.name) { newName ->
                    if (newName.isNotBlank()) brush.name = newName.trim()
                    subOverlay = BrushSubOverlay.NONE
                    hostView.invalidate()
                }
            }
            "BRUSH_ACT_DUPLICATE" -> {
                val dup = brush.copy(id = "b_${System.currentTimeMillis()}", name = "Duplicate")
                val insertIdx = (selectedBrushIndex + 1).coerceIn(0, set.brushes.size)
                set.brushes.add(insertIdx, dup)
                selectedBrushIndex = insertIdx
                subOverlay = BrushSubOverlay.NONE
                hostView.showBanner("Brush Duplicated")
            }
            "BRUSH_ACT_CHANGE_ICON" -> {
                val styles = BrushIconStyle.values()
                brush.iconStyle = styles[(brush.iconStyle.ordinal + 1) % styles.size]
                subOverlay = BrushSubOverlay.NONE
                hostView.showBanner("Brush Icon Updated")
            }
            "BRUSH_ACT_EXPORT_ICON" -> {
                exportBrushIconToStorage(brush)
                subOverlay = BrushSubOverlay.NONE
            }
            "BRUSH_ACT_DELETE" -> {
                if (set.brushes.size <= 1) {
                    hostView.showBanner("Cannot delete the only brush in this set")
                } else {
                    set.brushes.removeAt(selectedBrushIndex)
                    selectedBrushIndex = selectedBrushIndex.coerceIn(0, set.brushes.lastIndex)
                    subOverlay = BrushSubOverlay.NONE
                    hostView.showBanner("Brush Deleted")
                }
            }
            "TOGGLE_BRUSH_TYPES_DROPDOWN" -> {
                subOverlay = if (subOverlay == BrushSubOverlay.BRUSH_TYPES_DROPDOWN) BrushSubOverlay.NONE else BrushSubOverlay.BRUSH_TYPES_DROPDOWN
            }
            "SELECT_BRUSH_TYPE" -> {
                val types = SketchBrushType.values()
                if (hit.arg1 in types.indices) brush.brushType = types[hit.arg1]
                subOverlay = BrushSubOverlay.NONE
            }
            "SUBTAB_BASIC" -> settingsSubTab = BrushSettingsSubTab.BASIC
            "SUBTAB_ADVANCED" -> settingsSubTab = BrushSettingsSubTab.ADVANCED
            "TOGGLE_ADV_SECTION" -> expandedAdvancedSection = if (expandedAdvancedSection == hit.arg1) -1 else hit.arg1
            "BTN_RESET_BRUSH" -> {
                brush.resetToDefault()
                hostView.showBanner("Brush Reset to Default")
            }
            "COLOR_TOP_TAB" -> {
                if (hit.arg1 == 2) {
                    hostView.popupState = TopPopupState.NONE
                    hostView.activateOnCanvasEyedropper(forBackground = false)
                } else {
                    colorState.topTabIndex = hit.arg1
                }
            }
            "REVERT_PREV_COLOR" -> {
                activeColState.setFromColorInt(activeColState.previousColor, recordPrevious = true)
                applyLiveBackgroundColorChange()
            }
            "PICK_COMP_COLOR", "PICK_HIST_COLOR" -> {
                activeColState.setFromColorInt(hit.arg1, recordPrevious = true)
                applyLiveBackgroundColorChange()
            }
            "CYCLE_HARMONY_MODE" -> {
                activeColState.harmonyMode = (activeColState.harmonyMode + 1) % 4
            }
            "SHUFFLE_HISTORY_BAR" -> {
                activeColState.pushToHistory(ColorStudioState.hslToColor(Random.nextFloat() * 360f, 0.8f, 0.55f))
            }
            "TOGGLE_COLORLESS_ERASER" -> {
                colorState.isColorlessEraser = !colorState.isColorlessEraser
                hostView.showBanner(if (colorState.isColorlessEraser) "Transparent Colorless Mode ON" else "Color Mode Restored")
            }
            "ACTIVATE_EYEDROPPER" -> {
                hostView.popupState = TopPopupState.NONE
                hostView.activateOnCanvasEyedropper(forBackground = isBgColorMode)
            }
            "EDIT_HEX_COLOR" -> {
                val curHex = String.format("%06X", 0xFFFFFF and activeColState.currentColor)
                hostView.showSimpleInputModal("HEX Color (#RRGGBB)", curHex) { input ->
                    val cleaned = input.trim().removePrefix("#")
                    if (cleaned.length == 6) {
                        try {
                            val parsed = Color.parseColor("#$cleaned")
                            activeColState.setFromColorInt(parsed, recordPrevious = true)
                            applyLiveBackgroundColorChange()
                        } catch (_: Exception) {}
                    }
                    hostView.invalidate()
                }
            }
            "COLOR_BOTTOM_MODE" -> {
                when (hit.arg1) {
                    0, 1 -> activeColState.bottomModeIndex = hit.arg1
                    2 -> {
                        if (isBgColorMode) {
                            bgColorState.topTabIndex = 1
                        } else {
                            colorState.topTabIndex = if (colorState.topTabIndex == 1) 0 else 1
                        }
                    }
                    3 -> {
                        val randCol = ColorStudioState.hslToColor(Random.nextFloat() * 360f, Random.nextFloat().coerceIn(0.4f, 1f), Random.nextFloat().coerceIn(0.25f, 0.8f))
                        activeColState.setFromColorInt(randCol, recordPrevious = true)
                        applyLiveBackgroundColorChange()
                    }
                    4 -> activeColState.harmonyMode = (activeColState.harmonyMode + 1) % 4
                }
            }
        }
    }

    private fun updateSliderValue(id: String, touchX: Float) {
        val rect = sliderRects[id] ?: return
        val f = ((touchX - rect.left) / rect.width()).coerceIn(0f, 1f)
        val b = selectedBrush()
        val activeColState = if (activeBgModeInTouch) bgColorState else colorState
        val cfg = hostView.project.backgroundConfig

        when (id) {
            "SLIDER_BASIC_SIZE" -> b.size = 1f + f * 299f
            "SLIDER_BASIC_OPACITY" -> b.opacity = 1f + f * 99f
            "ADV_PRESS_SIZE_HEAVY" -> b.sizeHeavyPressure = f * 100f
            "ADV_PRESS_SIZE_LIGHT" -> b.sizeLightPressure = f * 100f
            "ADV_PRESS_OPAC_LIGHT" -> b.opacityLightPressure = f * 100f
            "ADV_STAMP_SPACING" -> b.spacing = 2f + f * 98f
            "ADV_STAMP_ROUND" -> b.roundness = 10f + f * 90f
            "ADV_STAMP_ROT" -> b.rotation = -180f + f * 360f
            "ADV_NIB_HARDNESS" -> b.hardness = f * 100f
            "ADV_NIB_GRAIN" -> b.grainStrength = f * 100f
            "ADV_RAND_SIZE" -> b.sizeJitter = f * 100f
            "ADV_RAND_OPAC" -> b.opacityJitter = f * 100f
            "ADV_RAND_SCATTER" -> b.scatterJitter = f * 100f
            "BG_SURF_INTENSITY" -> { cfg.grainIntensity = f * 100f; hostView.project.isDirty = true }
            "BG_SURF_SCALE" -> { cfg.textureScale = 25f + f * 225f; hostView.project.isDirty = true }
            "BG_SURF_WARMTH" -> { cfg.warmthTint = -50f + f * 100f; hostView.project.isDirty = true }
            "BG_GRID_SPACING" -> { cfg.gridSpacing = 20f + f * 280f; hostView.project.isDirty = true }
            "BG_GRID_OPACITY" -> { cfg.gridOpacity = 5f + f * 95f; hostView.project.isDirty = true }
            "BG_GRID_SUBDIV" -> { cfg.gridSubdivisions = 1f + f * 7f; hostView.project.isDirty = true }
            "BG_LIGHT_STRENGTH" -> { cfg.vignetteStrength = f * 100f; hostView.project.isDirty = true }
            "BG_LIGHT_SOFT" -> { cfg.vignetteSoftness = 10f + f * 90f; hostView.project.isDirty = true }
            "BG_LIGHT_ELEV" -> { cfg.focusElevation = f * 100f; hostView.project.isDirty = true }
            "COLOR_H" -> {
                activeColState.hue = f * 360f
                activeColState.syncColorFromHsl()
                applyLiveBackgroundColorChange()
            }
            "COLOR_S" -> {
                activeColState.saturation = f
                activeColState.syncColorFromHsl()
                applyLiveBackgroundColorChange()
            }
            "COLOR_L" -> {
                activeColState.lightness = f
                activeColState.syncColorFromHsl()
                applyLiveBackgroundColorChange()
            }
            "COLOR_R" -> {
                val nr = (f * 255f).roundToInt().coerceIn(0, 255)
                activeColState.setFromColorInt(Color.rgb(nr, Color.green(activeColState.currentColor), Color.blue(activeColState.currentColor)))
                applyLiveBackgroundColorChange()
            }
            "COLOR_G" -> {
                val ng = (f * 255f).roundToInt().coerceIn(0, 255)
                activeColState.setFromColorInt(Color.rgb(Color.red(activeColState.currentColor), ng, Color.blue(activeColState.currentColor)))
                applyLiveBackgroundColorChange()
            }
            "COLOR_B" -> {
                val nb = (f * 255f).roundToInt().coerceIn(0, 255)
                activeColState.setFromColorInt(Color.rgb(Color.red(activeColState.currentColor), Color.green(activeColState.currentColor), nb))
                applyLiveBackgroundColorChange()
            }
        }
    }

    private fun updateHueFromTouch(x: Float, y: Float, state: ColorStudioState) {
        var deg = Math.toDegrees(atan2((y - wheelCy).toDouble(), (x - wheelCx).toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        state.hue = (360f - deg) % 360f
        state.syncColorFromHsl()
        applyLiveBackgroundColorChange()
    }

    private fun hueToWheelAngleRad(hue: Float): Double {
        val deg = (360f - hue) % 360f
        return Math.toRadians(deg.toDouble())
    }

    private fun updateSlFromDiamondTouch(x: Float, y: Float, state: ColorStudioState) {
        val rawDx = (x - wheelCx) / diamondHalfDiag
        val rawDy = (y - wheelCy) / diamondHalfDiag

        val snapRadius = 0.16f
        when {
            hypot(rawDx, rawDy + 1f) < snapRadius || (rawDy <= -0.96f && abs(rawDx) < 0.25f) -> {
                state.lightness = 1.0f
                state.saturation = 0.0f
                state.syncColorFromHsl()
                applyLiveBackgroundColorChange()
                return
            }
            hypot(rawDx, rawDy - 1f) < snapRadius || (rawDy >= 0.96f && abs(rawDx) < 0.25f) -> {
                state.lightness = 0.0f
                state.saturation = 0.0f
                state.syncColorFromHsl()
                applyLiveBackgroundColorChange()
                return
            }
            hypot(rawDx - 1f, rawDy) < snapRadius || (rawDx >= 0.94f && abs(rawDy) < 0.28f) -> {
                state.lightness = 0.5f
                state.saturation = 1.0f
                state.syncColorFromHsl()
                applyLiveBackgroundColorChange()
                return
            }
            hypot(rawDx + 1f, rawDy) < snapRadius || (rawDx <= -0.94f && abs(rawDy) < 0.28f) -> {
                state.lightness = 0.5f
                state.saturation = 0.0f
                state.syncColorFromHsl()
                applyLiveBackgroundColorChange()
                return
            }
        }

        val dy = rawDy.coerceIn(-1f, 1f)
        val l = ((1f - dy) * 0.5f).coerceIn(0f, 1f)
        val maxSpanX = (1f - abs(dy)).coerceAtLeast(0.005f)
        val dxClamped = rawDx.coerceIn(-maxSpanX, maxSpanX)
        val s = (((dxClamped / maxSpanX) + 1f) * 0.5f).coerceIn(0f, 1f)
        state.lightness = l
        state.saturation = s
        state.syncColorFromHsl()
        applyLiveBackgroundColorChange()
    }

    private fun slToDiamondPoint(s: Float, l: Float, cx: Float, cy: Float, halfDiag: Float): PointF {
        val dy = (1f - 2f * l).coerceIn(-1f, 1f)
        val py = cy + dy * halfDiag
        val maxSpanX = (1f - abs(dy)) * halfDiag
        val px = cx + (2f * s - 1f) * maxSpanX
        return PointF(px, py)
    }

    private fun exportBrushIconToStorage(brush: SketchBrushItem) {
        try {
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            drawRealisticBrushIcon(c, RectF(28f, 28f, 228f, 228f), brush.iconStyle, 1.5f)
            val tmpProj = SketchProjectState(name = "${brush.name}_icon", width = 256, height = 256)
            tmpProj.ensureLayersInitialized()
            val lc = Canvas(tmpProj.activeLayer().bitmap)
            lc.drawBitmap(bmp, 0f, 0f, null)
            bmp.recycle()
            val res = ProjectStorageManager.saveProjectToDevice(hostView.context, tmpProj, asCopy = false)
            res.onSuccess { p -> hostView.showBanner("Exported Icon -> $p") }
                .onFailure { hostView.showBanner("Export failed") }
            tmpProj.releaseAll()
        } catch (_: Exception) {}
    }

    private fun drawLibraryTabIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        fillPaint.color = Color.rgb(225, 225, 225)
        strokePaint.color = Color.rgb(225, 225, 225)
        strokePaint.strokeWidth = size * 0.06f
        val w = size * 0.88f
        val topH = size * 0.24f
        c.drawRect(cx - w * 0.5f, cy - size * 0.34f, cx + w * 0.5f, cy - size * 0.34f + topH, fillPaint)
        val sq = size * 0.22f
        val gap = (w - sq * 3f) * 0.5f
        for (i in 0..2) {
            val sx = cx - w * 0.5f + i * (sq + gap)
            c.drawRect(sx, cy + size * 0.04f, sx + sq, cy + size * 0.04f + sq * 0.85f, strokePaint)
        }
    }

    private fun drawSlidersTabIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(232, 232, 232)
        strokePaint.strokeWidth = size * 0.07f
        fillPaint.color = Color.rgb(232, 232, 232)
        val w = size * 0.86f
        val y1 = cy - size * 0.16f
        val y2 = cy + size * 0.16f
        c.drawLine(cx - w * 0.5f, y1, cx + w * 0.5f, y1, strokePaint)
        c.drawLine(cx - w * 0.5f, y2, cx + w * 0.5f, y2, strokePaint)
        c.drawCircle(cx - w * 0.14f, y1, size * 0.13f, fillPaint)
        c.drawCircle(cx + w * 0.18f, y2, size * 0.13f, fillPaint)
    }

    private fun drawPinCircleIcon(c: Canvas, cx: Float, cy: Float, r: Float, active: Boolean) {
        fillPaint.color = if (active) Color.rgb(20, 120, 216) else Color.rgb(104, 104, 104)
        c.drawCircle(cx, cy, r, fillPaint)
        fillPaint.color = Color.WHITE
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = r * 0.14f
        c.drawRect(cx - r * 0.34f, cy - r * 0.48f, cx + r * 0.34f, cy - r * 0.32f, fillPaint)
        c.drawRect(cx - r * 0.20f, cy - r * 0.32f, cx + r * 0.20f, cy + r * 0.08f, fillPaint)
        c.drawLine(cx - r * 0.38f, cy + r * 0.08f, cx + r * 0.38f, cy + r * 0.08f, strokePaint)
        c.drawLine(cx, cy + r * 0.08f, cx, cy + r * 0.55f, strokePaint)
    }

    private fun drawRenameBoxIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val w = size * 1.15f
        val h = size * 0.85f
        fillPaint.color = Color.WHITE
        c.drawRect(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, fillPaint)
        strokePaint.color = Color.rgb(95, 95, 95)
        strokePaint.strokeWidth = size * 0.05f
        c.drawRect(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, strokePaint)

        textPaint.color = Color.rgb(75, 75, 75)
        textPaint.textSize = size * 0.48f
        textPaint.isFakeBoldText = true
        textPaint.textAlign = Paint.Align.CENTER
        c.drawText("N", cx - w * 0.12f, cy + size * 0.17f, textPaint)
        textPaint.isFakeBoldText = false
        c.drawLine(cx + w * 0.18f, cy - h * 0.32f, cx + w * 0.18f, cy + h * 0.32f, strokePaint)
    }

    private fun drawCreateSetIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(88, 88, 88)
        strokePaint.strokeWidth = size * 0.09f
        val w = size * 1.05f
        val h = size * 0.46f
        c.drawRoundRect(RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f), size * 0.08f, size * 0.08f, strokePaint)
        val p = size * 0.14f
        c.drawLine(cx - p, cy, cx + p, cy, strokePaint)
        c.drawLine(cx, cy - p, cx, cy + p, strokePaint)
    }

    private fun drawDuplicateNibIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        fillPaint.color = Color.rgb(88, 88, 88)
        strokePaint.color = Color.rgb(88, 88, 88)
        strokePaint.strokeWidth = size * 0.09f
        val p = Path().apply {
            moveTo(cx, cy - size * 0.48f)
            lineTo(cx - size * 0.28f, cy + size * 0.05f)
            lineTo(cx + size * 0.28f, cy + size * 0.05f)
            close()
        }
        c.drawPath(p, fillPaint)
        val py = cy + size * 0.32f
        val r = size * 0.16f
        c.drawLine(cx - r, py, cx + r, py, strokePaint)
        c.drawLine(cx, py - r, cx, py + r, strokePaint)
    }

    private fun drawChangeIconBox(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(88, 88, 88)
        strokePaint.strokeWidth = size * 0.07f
        fillPaint.color = Color.rgb(88, 88, 88)
        val s = size * 0.45f
        c.drawRect(cx - s, cy - s, cx + s, cy + s, strokePaint)
        val p = Path().apply {
            moveTo(cx, cy - s * 0.55f)
            lineTo(cx - s * 0.48f, cy + s * 0.45f)
            lineTo(cx + s * 0.48f, cy + s * 0.45f)
            close()
        }
        c.drawPath(p, fillPaint)
    }

    private fun drawExportIconBox(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(88, 88, 88)
        strokePaint.strokeWidth = size * 0.07f
        fillPaint.color = Color.rgb(88, 88, 88)
        val s = size * 0.38f
        c.drawRect(cx - s, cy - s * 0.5f, cx + s, cy + s * 1.1f, strokePaint)
        c.drawLine(cx, cy, cx, cy - s * 1.25f, strokePaint)
        c.drawLine(cx - s * 0.45f, cy - s * 0.8f, cx, cy - s * 1.25f, strokePaint)
        c.drawLine(cx + s * 0.45f, cy - s * 0.8f, cx, cy - s * 1.25f, strokePaint)
    }

    private fun drawSelectorRing(c: Canvas, cx: Float, cy: Float, r: Float, innerColor: Int) {
        fillPaint.color = innerColor
        c.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = r * 0.24f
        c.drawCircle(cx, cy, r + r * 0.1f, strokePaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = r * 0.16f
        c.drawCircle(cx, cy, r, strokePaint)
    }

    private fun drawHarmonyRingsIcon(c: Canvas, cx: Float, cy: Float, r: Float) {
        strokePaint.color = Color.rgb(225, 225, 225)
        strokePaint.strokeWidth = r * 0.20f
        c.drawCircle(cx, cy - r * 0.38f, r * 0.55f, strokePaint)
        c.drawCircle(cx - r * 0.38f, cy + r * 0.28f, r * 0.55f, strokePaint)
        c.drawCircle(cx + r * 0.38f, cy + r * 0.28f, r * 0.55f, strokePaint)
    }

    private fun drawBarcodeIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        strokePaint.color = color
        val w = size * 0.9f
        val h = size * 0.7f
        for (i in -3..3) {
            strokePaint.strokeWidth = if (i % 2 == 0) size * 0.13f else size * 0.07f
            val x = cx + i * (w / 6.5f)
            c.drawLine(x, cy - h * 0.5f, x, cy + h * 0.5f, strokePaint)
        }
    }

    private fun drawSmall3x2GridIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        fillPaint.color = color
        val cell = size * 0.32f
        val gap = size * 0.12f
        for (r in 0..1) {
            for (col in -1..1) {
                val x = cx + col * (cell + gap) - cell * 0.5f
                val y = cy + (r - 0.5f) * (cell + gap) - cell * 0.3f
                c.drawRect(x, y, x + cell, y + cell * 0.75f, fillPaint)
            }
        }
    }

    private fun drawShuffleArrowsIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.14f
        val w = size * 0.75f
        val h = size * 0.38f
        c.drawLine(cx - w, cy - h, cx + w, cy + h, strokePaint)
        c.drawLine(cx - w, cy + h, cx + w, cy - h, strokePaint)
    }

    private fun drawTransparentEraserButton(c: Canvas, cx: Float, cy: Float, r: Float, active: Boolean) {
        val clipCircle = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        c.save()
        c.clipPath(clipCircle)

        val sq = (r * 2f) / 6f
        val startX = cx - r
        val startY = cy - r
        for (row in 0..5) {
            for (col in 0..5) {
                fillPaint.color = if ((row + col) % 2 == 0) Color.rgb(154, 154, 154) else Color.rgb(66, 66, 66)
                c.drawRect(startX + col * sq, startY + row * sq, startX + (col + 1) * sq, startY + (row + 1) * sq, fillPaint)
            }
        }

        val cylW = r * 0.82f
        val cylLeft = cx - cylW * 0.5f
        val cylRight = cx + cylW * 0.5f
        fillPaint.color = Color.WHITE
        c.drawRoundRect(RectF(cylLeft, cy - r * 0.26f, cylRight, cy + r * 1.05f), r * 0.16f, r * 0.16f, fillPaint)
        fillPaint.color = Color.rgb(32, 32, 32)
        c.drawRect(cylLeft, cy - r * 0.02f, cylRight, cy + r * 0.18f, fillPaint)
        strokePaint.color = Color.rgb(32, 32, 32)
        strokePaint.strokeWidth = 2.5f
        c.drawRoundRect(RectF(cylLeft, cy - r * 0.26f, cylRight, cy + r * 1.05f), r * 0.16f, r * 0.16f, strokePaint)
        c.restore()

        if (active) {
            strokePaint.color = Color.rgb(65, 148, 238)
            strokePaint.strokeWidth = 5.5f
            c.drawCircle(cx, cy, r + 4f, strokePaint)
        }
    }

    private fun drawEyedropperIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        c.save()
        c.translate(cx, cy)
        c.rotate(45f)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = size * 0.11f
        fillPaint.color = Color.WHITE
        c.drawRect(-size * 0.14f, -size * 0.65f, size * 0.14f, -size * 0.22f, fillPaint)
        c.drawLine(-size * 0.28f, -size * 0.22f, size * 0.28f, -size * 0.22f, strokePaint)
        c.drawRect(-size * 0.11f, -size * 0.22f, size * 0.11f, size * 0.45f, strokePaint)
        c.drawLine(0f, size * 0.45f, 0f, size * 0.68f, strokePaint)
        c.restore()
    }

    private fun drawBottomBrushSliderCircleIcon(c: Canvas, cx: Float, cy: Float, size: Float, isColorMode: Boolean) {
        val col = if (isColorMode) Color.rgb(69, 149, 230) else Color.WHITE
        fillPaint.color = col
        strokePaint.color = col
        strokePaint.strokeWidth = size * 0.12f
        val p = Path().apply {
            moveTo(cx, cy - size * 0.72f)
            lineTo(cx - size * 0.38f, cy + size * 0.18f)
            lineTo(cx + size * 0.38f, cy + size * 0.18f)
            close()
        }
        c.drawPath(p, fillPaint)
        c.drawLine(cx - size * 0.75f, cy + size * 0.48f, cx + size * 0.75f, cy + size * 0.48f, strokePaint)
        c.drawCircle(cx, cy + size * 0.48f, size * 0.16f, fillPaint)
    }

    private fun drawCornerScrollArrows(c: Canvas, cx: Float, cy: Float, size: Float) {
        fillPaint.color = Color.rgb(115, 115, 115)
        val p1 = Path().apply {
            moveTo(cx, cy - size)
            lineTo(cx - size * 0.65f, cy - size * 0.2f)
            lineTo(cx + size * 0.65f, cy - size * 0.2f)
            close()
        }
        val p2 = Path().apply {
            moveTo(cx, cy + size)
            lineTo(cx - size * 0.65f, cy + size * 0.2f)
            lineTo(cx + size * 0.65f, cy + size * 0.2f)
            close()
        }
        c.drawPath(p1, fillPaint)
        c.drawPath(p2, fillPaint)
    }

    fun drawRealisticBrushIcon(c: Canvas, box: RectF, style: BrushIconStyle, sx: Float) {
        val cx = box.centerX()
        val cy = box.centerY()
        val top = box.top
        val bot = box.bottom
        val w = box.width()
        val h = box.height()

        when (style) {
            BrushIconStyle.WOOD_PENCIL -> {
                val cone = Path().apply {
                    moveTo(cx, top + h * 0.06f)
                    lineTo(cx - w * 0.28f, top + h * 0.58f)
                    lineTo(cx + w * 0.28f, top + h * 0.58f)
                    close()
                }
                fillPaint.color = Color.rgb(218, 188, 158)
                c.drawPath(cone, fillPaint)
                val tip = Path().apply {
                    moveTo(cx, top + h * 0.06f)
                    lineTo(cx - w * 0.10f, top + h * 0.25f)
                    lineTo(cx + w * 0.10f, top + h * 0.25f)
                    close()
                }
                fillPaint.color = Color.rgb(42, 42, 42)
                c.drawPath(tip, fillPaint)
                fillPaint.color = Color.rgb(38, 38, 38)
                c.drawRect(cx - w * 0.28f, top + h * 0.58f, cx + w * 0.28f, bot - h * 0.06f, fillPaint)
                fillPaint.color = Color.rgb(205, 180, 115)
                c.drawRect(cx - w * 0.28f, bot - h * 0.22f, cx + w * 0.28f, bot - h * 0.14f, fillPaint)
            }
            BrushIconStyle.HARD_ERASER, BrushIconStyle.SOFT_ERASER -> {
                fillPaint.color = Color.rgb(235, 235, 235)
                val isRound = (style == BrushIconStyle.SOFT_ERASER)
                if (isRound) {
                    c.drawRoundRect(RectF(cx - w * 0.36f, top + h * 0.25f, cx + w * 0.36f, top + h * 0.65f), 14f * sx, 14f * sx, fillPaint)
                } else {
                    c.drawRect(cx - w * 0.36f, top + h * 0.25f, cx + w * 0.36f, top + h * 0.55f, fillPaint)
                }
                fillPaint.color = Color.rgb(125, 130, 135)
                c.drawRect(cx - w * 0.40f, top + h * 0.52f, cx + w * 0.40f, bot - h * 0.08f, fillPaint)
            }
            BrushIconStyle.CHISEL_MARKER -> {
                val nib = Path().apply {
                    moveTo(cx - w * 0.14f, top + h * 0.12f)
                    lineTo(cx + w * 0.16f, top + h * 0.24f)
                    lineTo(cx + w * 0.16f, top + h * 0.48f)
                    lineTo(cx - w * 0.14f, top + h * 0.48f)
                    close()
                }
                fillPaint.color = Color.rgb(210, 210, 210)
                c.drawPath(nib, fillPaint)
                fillPaint.color = Color.rgb(55, 55, 55)
                c.drawRect(cx - w * 0.32f, top + h * 0.48f, cx + w * 0.32f, bot - h * 0.08f, fillPaint)
                fillPaint.color = Color.rgb(205, 180, 115)
                c.drawRect(cx - w * 0.26f, top + h * 0.48f, cx + w * 0.26f, top + h * 0.62f, fillPaint)
            }
            BrushIconStyle.AIRBRUSH -> {
                fillPaint.color = Color.rgb(75, 75, 75)
                c.drawRect(cx - w * 0.14f, top + h * 0.12f, cx + w * 0.14f, top + h * 0.34f, fillPaint)
                fillPaint.color = Color.rgb(212, 190, 135)
                val cone = Path().apply {
                    moveTo(cx - w * 0.18f, top + h * 0.34f)
                    lineTo(cx + w * 0.18f, top + h * 0.34f)
                    lineTo(cx + w * 0.34f, top + h * 0.64f)
                    lineTo(cx - w * 0.34f, top + h * 0.64f)
                    close()
                }
                c.drawPath(cone, fillPaint)
                fillPaint.color = Color.rgb(65, 65, 65)
                c.drawRect(cx - w * 0.36f, top + h * 0.64f, cx + w * 0.36f, bot - h * 0.08f, fillPaint)
            }
            BrushIconStyle.TECHNICAL_PEN -> {
                fillPaint.color = Color.rgb(90, 90, 90)
                c.drawRect(cx - w * 0.04f, top + h * 0.06f, cx + w * 0.04f, top + h * 0.24f, fillPaint)
                fillPaint.color = Color.rgb(185, 185, 185)
                c.drawRect(cx - w * 0.11f, top + h * 0.22f, cx + w * 0.11f, top + h * 0.36f, fillPaint)
                val body = Path().apply {
                    moveTo(cx - w * 0.18f, top + h * 0.36f)
                    lineTo(cx + w * 0.18f, top + h * 0.36f)
                    lineTo(cx + w * 0.28f, bot - h * 0.16f)
                    lineTo(cx - w * 0.28f, bot - h * 0.16f)
                    close()
                }
                fillPaint.color = Color.rgb(142, 146, 150)
                c.drawPath(body, fillPaint)
                fillPaint.color = Color.rgb(205, 182, 120)
                c.drawRect(cx - w * 0.28f, bot - h * 0.18f, cx + w * 0.28f, bot - h * 0.10f, fillPaint)
            }
            BrushIconStyle.FOUNTAIN_PEN -> {
                val nib = Path().apply {
                    moveTo(cx, top + h * 0.08f)
                    lineTo(cx - w * 0.22f, top + h * 0.54f)
                    lineTo(cx - w * 0.14f, top + h * 0.72f)
                    lineTo(cx + w * 0.14f, top + h * 0.72f)
                    lineTo(cx + w * 0.22f, top + h * 0.54f)
                    close()
                }
                fillPaint.color = Color.rgb(218, 195, 138)
                c.drawPath(nib, fillPaint)
                strokePaint.color = Color.rgb(60, 60, 60)
                strokePaint.strokeWidth = 2f * sx
                c.drawLine(cx, top + h * 0.08f, cx, top + h * 0.45f, strokePaint)
                fillPaint.color = Color.rgb(65, 65, 65)
                c.drawRect(cx - w * 0.18f, top + h * 0.72f, cx + w * 0.18f, bot - h * 0.08f, fillPaint)
            }
            BrushIconStyle.ROUND_BRUSH, BrushIconStyle.FELT_PEN -> {
                val hair = Path().apply {
                    moveTo(cx, top + h * 0.08f)
                    quadTo(cx - w * 0.26f, top + h * 0.36f, cx - w * 0.16f, top + h * 0.62f)
                    lineTo(cx + w * 0.16f, top + h * 0.62f)
                    quadTo(cx + w * 0.26f, top + h * 0.36f, cx, top + h * 0.08f)
                    close()
                }
                fillPaint.color = Color.rgb(122, 78, 48)
                c.drawPath(hair, fillPaint)
                fillPaint.color = Color.rgb(205, 182, 120)
                c.drawRect(cx - w * 0.18f, top + h * 0.62f, cx + w * 0.18f, top + h * 0.74f, fillPaint)
                fillPaint.color = Color.rgb(45, 45, 45)
                c.drawRect(cx - w * 0.22f, top + h * 0.74f, cx + w * 0.22f, bot - h * 0.08f, fillPaint)
            }
            BrushIconStyle.BLENDER_CONE -> {
                val cone = Path().apply {
                    moveTo(cx, top + h * 0.10f)
                    lineTo(cx - w * 0.22f, top + h * 0.72f)
                    lineTo(cx + w * 0.22f, top + h * 0.72f)
                    close()
                }
                fillPaint.color = Color.rgb(210, 210, 210)
                c.drawPath(cone, fillPaint)
                fillPaint.color = Color.rgb(75, 75, 75)
                c.drawRect(cx - w * 0.22f, top + h * 0.72f, cx + w * 0.22f, bot - h * 0.08f, fillPaint)
            }
            BrushIconStyle.PASTEL_BLOCK, BrushIconStyle.CHARCOAL_STICK -> {
                fillPaint.color = Color.rgb(38, 38, 38)
                c.drawRoundRect(RectF(cx - w * 0.22f, top + h * 0.24f, cx + w * 0.22f, bot - h * 0.08f), 6f * sx, 6f * sx, fillPaint)
                fillPaint.color = Color.rgb(205, 182, 120)
                c.drawRect(cx - w * 0.22f, bot - h * 0.22f, cx + w * 0.22f, bot - h * 0.14f, fillPaint)
            }
            BrushIconStyle.FLAT_BRISTLE, BrushIconStyle.FAN_BRUSH -> {
                fillPaint.color = if (style == BrushIconStyle.FAN_BRUSH) Color.rgb(215, 215, 215) else Color.rgb(118, 86, 56)
                val topW = if (style == BrushIconStyle.FAN_BRUSH) w * 0.36f else w * 0.24f
                val head = Path().apply {
                    moveTo(cx - topW, top + h * 0.16f)
                    lineTo(cx + topW, top + h * 0.16f)
                    lineTo(cx + w * 0.22f, top + h * 0.66f)
                    lineTo(cx - w * 0.22f, top + h * 0.66f)
                    close()
                }
                c.drawPath(head, fillPaint)
                fillPaint.color = Color.rgb(205, 182, 120)
                c.drawRect(cx - w * 0.24f, top + h * 0.66f, cx + w * 0.24f, top + h * 0.76f, fillPaint)
                fillPaint.color = Color.rgb(55, 55, 55)
                c.drawRect(cx - w * 0.26f, top + h * 0.76f, cx + w * 0.26f, bot - h * 0.08f, fillPaint)
            }
            BrushIconStyle.DIAMOND_SHADER -> {
                val p = Path().apply {
                    moveTo(cx, top + h * 0.14f)
                    lineTo(cx + w * 0.24f, cy)
                    lineTo(cx, bot - h * 0.14f)
                    lineTo(cx - w * 0.24f, cy)
                    close()
                }
                fillPaint.color = Color.rgb(22, 22, 22)
                c.drawPath(p, fillPaint)
            }
            BrushIconStyle.TEXTURE_FRACTAL -> {
                fillPaint.shader = LinearGradient(box.left, box.top, box.right, box.bottom, Color.rgb(18, 32, 78), Color.rgb(0, 185, 215), Shader.TileMode.CLAMP)
                c.drawRect(box, fillPaint)
                fillPaint.shader = null
                strokePaint.color = Color.argb(160, 180, 255, 255)
                strokePaint.strokeWidth = 2f * sx
                c.drawLine(box.left, box.top, box.right, box.bottom, strokePaint)
                c.drawLine(box.right, box.top, box.left, box.bottom, strokePaint)
                c.drawCircle(cx, box.centerY(), w * 0.28f, strokePaint)
            }
            BrushIconStyle.TEXTURE_HATCH -> {
                fillPaint.color = Color.rgb(62, 62, 62)
                c.drawRect(box, fillPaint)
                strokePaint.color = Color.rgb(185, 185, 185)
                strokePaint.strokeWidth = 1.8f * sx
                for (i in 0..5) {
                    val off = i * (w / 5f)
                    c.drawLine(box.left + off, box.top, box.left, box.top + off, strokePaint)
                    c.drawLine(box.right - off, box.top, box.right, box.top + off, strokePaint)
                }
            }
            BrushIconStyle.TEXTURE_FROST, BrushIconStyle.TEXTURE_SPLATTER -> {
                fillPaint.color = Color.rgb(22, 22, 22)
                c.drawRect(box, fillPaint)
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 2f * sx
                val r = w * 0.38f
                val by = box.centerY()
                for (a in 0 until 8) {
                    val rad = Math.toRadians((a * 45).toDouble())
                    c.drawLine(cx, by, cx + cos(rad).toFloat() * r, by + sin(rad).toFloat() * r, strokePaint)
                }
            }
            BrushIconStyle.TEXTURE_NOISE -> {
                fillPaint.color = Color.rgb(35, 35, 35)
                c.drawRect(box, fillPaint)
                fillPaint.color = Color.rgb(225, 225, 225)
                val step = w / 6f
                for (rx in 0..5) {
                    for (ry in 0..5) {
                        if ((rx * 3 + ry * 7) % 2 == 0) {
                            c.drawRect(box.left + rx * step, box.top + ry * step, box.left + rx * step + step * 0.6f, box.top + ry * step + step * 0.6f, fillPaint)
                        }
                    }
                }
            }
            BrushIconStyle.TEXTURE_GRID -> {
                fillPaint.color = Color.rgb(25, 25, 45)
                c.drawRect(box, fillPaint)
                val cols = intArrayOf(Color.CYAN, Color.MAGENTA, Color.GREEN, Color.YELLOW)
                val step = w / 4f
                for (gx in 0..3) {
                    for (gy in 0..3) {
                        fillPaint.color = cols[(gx + gy) % cols.size]
                        c.drawRect(box.left + gx * step + 2f, box.top + gy * step + 2f, box.left + (gx + 1) * step - 2f, box.top + (gy + 1) * step - 2f, fillPaint)
                    }
                }
            }
        }
    }
}
