package com.sketchbook.oss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ModUiPalette(
    val workspaceBackdrop: Int = Color.rgb(127, 127, 127),
    val topBarBg: Int = Color.rgb(239, 239, 239),
    val topBarActiveSlot: Int = Color.rgb(220, 220, 220),
    val popupBg: Int = Color.rgb(243, 243, 243),
    val popupHeaderBg: Int = Color.rgb(236, 236, 236),
    val cardBg: Int = Color.rgb(249, 249, 249),
    val inputBg: Int = Color.WHITE,
    val textPrimary: Int = Color.rgb(20, 20, 20),
    val textSecondary: Int = Color.rgb(100, 100, 100),
    val iconTint: Int = Color.rgb(104, 104, 104),
    val accentColor: Int = Color.rgb(55, 116, 205),
    val borderColor: Int = Color.rgb(205, 205, 205),
    val paperTint: Int = Color.WHITE
)

enum class TopPopupState {
    NONE,
    BUTTON1_MAIN,
    NEW_SKETCH,
    EDIT_PRESET,
    SAVE_MENU,
    SHARE_EXPORT_MAIN,
    EXPORT_AS_MENU,
    SHARE_AS_MENU,
    BRUSH_MENU,
    COLOR_MENU,
    BG_COLOR_MENU,
    LAYER_SETTINGS_MODAL,
    BLEND_MODE_MODAL,
    COLOR_LABEL_MODAL,
    LAYER_ADJUST_MODAL,
    PREFERENCES_SCREEN
}

enum class NewSketchTab {
    PLUS_CUSTOM,
    LIST_PRESETS
}

enum class BrushModalTopTab {
    LIBRARY_GRID,
    SETTINGS_SLIDERS
}

enum class BrushSettingsSubTab {
    BASIC,
    ADVANCED
}

enum class BrushSubOverlay {
    NONE,
    SET_SETTINGS,
    BRUSH_ACTIONS,
    BRUSH_TYPES_DROPDOWN
}

enum class SketchBrushType(val title: String) {
    STANDARD("Standard"),
    MARKER("Marker"),
    SMUDGE("Smudge"),
    GLOW("Glow"),
    ERASER("Eraser")
}

enum class BrushIconStyle {
    WOOD_PENCIL,
    HARD_ERASER,
    SOFT_ERASER,
    CHISEL_MARKER,
    AIRBRUSH,
    TECHNICAL_PEN,
    DIAMOND_SHADER,
    FOUNTAIN_PEN,
    ROUND_BRUSH,
    FELT_PEN,
    BLENDER_CONE,
    PASTEL_BLOCK,
    CHARCOAL_STICK,
    FLAT_BRISTLE,
    FAN_BRUSH,
    TEXTURE_FRACTAL,
    TEXTURE_HATCH,
    TEXTURE_FROST,
    TEXTURE_SPLATTER,
    TEXTURE_NOISE,
    TEXTURE_GRID
}

enum class LayerBlendMode(val title: String, val groupIndex: Int) {
    NORMAL("Normal", 0),

    ALPHA_MASK("Alpha Mask", 1),
    INVERSE_ALPHA_MASK("Inverse Alpha Mask", 1),

    DARKEN("Darken", 2),
    MULTIPLY("Multiply", 2),
    COLOR_BURN("Color Burn", 2),
    LINEAR_BURN("Linear Burn", 2),

    LIGHTEN("Lighten", 3),
    SCREEN("Screen", 3),
    COLOR_DODGE("Color Dodge", 3),
    LINEAR_DODGE("Linear Dodge (Add)", 3),
    GLOW("Glow", 3),
    SOFT_GLOW("Soft Glow", 3),

    OVERLAY("Overlay", 4),
    SOFT_LIGHT("Soft Light", 4),
    HARD_LIGHT("Hard Light", 4),

    HUE("Hue", 5),
    SATURATION("Saturation", 5),
    COLOR("Color", 5),
    LUMINOSITY("Luminosity", 5);

    companion object {
        fun fromNameOrDefault(name: String): LayerBlendMode {
            return values().firstOrNull { it.name == name || it.title.equals(name, ignoreCase = true) } ?: NORMAL
        }
    }
}

object ColorLabelPalette {
    private const val PREF_KEY = "custom_color_label_names_v1"

    // Index 0 = None, Indices 1..20 = 20 distinct curated colors
    val colors: IntArray = intArrayOf(
        Color.WHITE,               // 0: None
        Color.rgb(230, 25, 25),    // 1: Crimson Red
        Color.rgb(245, 130, 20),   // 2: Vivid Orange
        Color.rgb(250, 220, 15),   // 3: Canary Yellow
        Color.rgb(125, 225, 15),   // 4: Chartreuse Lime
        Color.rgb(25, 195, 75),    // 5: Emerald Green
        Color.rgb(0, 185, 165),    // 6: Teal Cyan
        Color.rgb(15, 130, 235),   // 7: Cobalt Blue
        Color.rgb(65, 60, 225),    // 8: Ultramarine
        Color.rgb(165, 30, 235),   // 9: Electric Violet
        Color.rgb(235, 40, 165),   // 10: Magenta Rose
        Color.rgb(145, 145, 145),  // 11: Neutral Gray
        Color.rgb(255, 110, 110),  // 12: Coral Salmon
        Color.rgb(215, 165, 95),   // 13: Warm Ochre
        Color.rgb(140, 90, 45),    // 14: Burnt Umber
        Color.rgb(35, 135, 95),    // 15: Forest Pine
        Color.rgb(85, 195, 245),   // 16: Sky Azure
        Color.rgb(135, 120, 245),  // 17: Periwinkle
        Color.rgb(215, 120, 235),  // 18: Orchid Lavender
        Color.rgb(95, 115, 135),   // 19: Slate Steel
        Color.rgb(45, 45, 50)      // 20: Charcoal Ink
    )

    val defaultNames: Array<String> = arrayOf(
        "None",
        "Red",
        "Orange",
        "Yellow",
        "Lime",
        "Green",
        "Teal",
        "Blue",
        "Indigo",
        "Violet",
        "Magenta",
        "Gray",
        "Coral",
        "Ochre",
        "Umber",
        "Forest",
        "Sky",
        "Periwinkle",
        "Orchid",
        "Slate",
        "Charcoal"
    )

    val customNames: Array<String> = defaultNames.copyOf()

    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("sketchbook_classic_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 1 until min(customNames.size, arr.length())) {
                val s = arr.optString(i, defaultNames[i]).trim()
                customNames[i] = if (s.isNotEmpty()) s else defaultNames[i]
            }
        } catch (_: Exception) {}
    }

    fun saveToPrefs(context: Context) {
        val arr = JSONArray()
        for (s in customNames) arr.put(s)
        context.getSharedPreferences("sketchbook_classic_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, arr.toString())
            .apply()
    }

    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (s in customNames) arr.put(s)
        return arr
    }

    fun fromJsonArray(arr: JSONArray?) {
        if (arr == null) return
        for (i in 1 until min(customNames.size, arr.length())) {
            val s = arr.optString(i, defaultNames[i]).trim()
            if (s.isNotEmpty()) customNames[i] = s
        }
    }
}

data class BackgroundProceduralConfig(
    var studioSubTab: Int = 0,          // 0 = SURFACE, 1 = GRIDS, 2 = LIGHTING
    var surfacePreset: Int = 0,         // 0=Smooth, 1=Cold Press, 2=Rough Canvas, 3=Charcoal Laid, 4=Halftone Dot, 5=Parchment
    var grainIntensity: Float = 35f,    // 0..100
    var textureScale: Float = 100f,     // 25..250
    var warmthTint: Float = 0f,         // -50..50

    var gridPreset: Int = 0,            // 0=None, 1=Square, 2=Isometric, 3=Perspective, 4=Dot Matrix, 5=Rule of Thirds
    var gridSpacing: Float = 80f,       // 20..300
    var gridOpacity: Float = 35f,       // 5..100
    var gridSubdivisions: Float = 4f,   // 1..8
    var invertGridContrast: Boolean = false,

    var lightingPreset: Int = 0,        // 0=Flat, 1=Radial Vignette, 2=Linear Horizon, 3=Spotlight
    var vignetteStrength: Float = 40f,  // 0..100
    var vignetteSoftness: Float = 60f,  // 10..100
    var focusElevation: Float = 50f     // 0..100
) {
    fun reset() {
        surfacePreset = 0
        grainIntensity = 35f
        textureScale = 100f
        warmthTint = 0f
        gridPreset = 0
        gridSpacing = 80f
        gridOpacity = 35f
        gridSubdivisions = 4f
        invertGridContrast = false
        lightingPreset = 0
        vignetteStrength = 40f
        vignetteSoftness = 60f
        focusElevation = 50f
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("surfacePreset", surfacePreset)
            put("grainIntensity", grainIntensity.toDouble())
            put("textureScale", textureScale.toDouble())
            put("warmthTint", warmthTint.toDouble())
            put("gridPreset", gridPreset)
            put("gridSpacing", gridSpacing.toDouble())
            put("gridOpacity", gridOpacity.toDouble())
            put("gridSubdivisions", gridSubdivisions.toDouble())
            put("invertGridContrast", invertGridContrast)
            put("lightingPreset", lightingPreset)
            put("vignetteStrength", vignetteStrength.toDouble())
            put("vignetteSoftness", vignetteSoftness.toDouble())
            put("focusElevation", focusElevation.toDouble())
        }
    }

    fun fromJson(o: JSONObject?) {
        if (o == null) return
        surfacePreset = o.optInt("surfacePreset", 0).coerceIn(0, 5)
        grainIntensity = o.optDouble("grainIntensity", 35.0).toFloat().coerceIn(0f, 100f)
        textureScale = o.optDouble("textureScale", 100.0).toFloat().coerceIn(25f, 250f)
        warmthTint = o.optDouble("warmthTint", 0.0).toFloat().coerceIn(-50f, 50f)
        gridPreset = o.optInt("gridPreset", 0).coerceIn(0, 5)
        gridSpacing = o.optDouble("gridSpacing", 80.0).toFloat().coerceIn(20f, 300f)
        gridOpacity = o.optDouble("gridOpacity", 35.0).toFloat().coerceIn(5f, 100f)
        gridSubdivisions = o.optDouble("gridSubdivisions", 4.0).toFloat().coerceIn(1f, 8f)
        invertGridContrast = o.optBoolean("invertGridContrast", false)
        lightingPreset = o.optInt("lightingPreset", 0).coerceIn(0, 3)
        vignetteStrength = o.optDouble("vignetteStrength", 40.0).toFloat().coerceIn(0f, 100f)
        vignetteSoftness = o.optDouble("vignetteSoftness", 60.0).toFloat().coerceIn(10f, 100f)
        focusElevation = o.optDouble("focusElevation", 50.0).toFloat().coerceIn(0f, 100f)
    }
}

class SketchLayer(
    val id: String,
    var name: String,
    var bitmap: Bitmap,
    var visible: Boolean = true,
    var alphaLock: Boolean = false,
    var locked: Boolean = false,
    var clippingMask: Boolean = false,
    var opacity: Float = 100f,
    var blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
    var colorLabelIndex: Int = 0,
    var temporalStrokesJson: String = ""
) {
    var thumbnailBitmap: Bitmap? = null
    var isThumbnailDirty: Boolean = true
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Computes a 1-to-1 square thumbnail cropped tightly around the drawn strokes
     * with a 12% margin, strictly clamped inside the canvas bounds [0, 0, W, H]
     * so the preview never goes outside the canvas or distorts aspect ratio.
     */
    fun getOrUpdateThumbnail(): Bitmap {
        val existing = thumbnailBitmap
        if (!isThumbnailDirty && existing != null && !existing.isRecycled) {
            return existing
        }
        val thumbSize = 240
        val thumb = if (existing != null && !existing.isRecycled) {
            existing
        } else {
            Bitmap.createBitmap(thumbSize, thumbSize, Bitmap.Config.ARGB_8888).also {
                thumbnailBitmap = it
            }
        }
        val c = Canvas(thumb)
        c.drawColor(Color.WHITE)

        if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            val w = bitmap.width
            val h = bitmap.height
            // Downsample scan with step to find non-transparent bounding box in < 0.8ms
            val step = max(1, min(w, h) / 180)
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1

            val rowBuf = IntArray(w)
            var y = 0
            while (y < h) {
                bitmap.getPixels(rowBuf, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    if ((rowBuf[x] ushr 24) > 4) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                    x += step
                }
                y += step
            }

            if (maxX >= minX && maxY >= minY) {
                // Expand by step + 12% margin
                val rawW = (maxX - minX + 1 + step * 2).toFloat()
                val rawH = (maxY - minY + 1 + step * 2).toFloat()
                val margin = max(16f, max(rawW, rawH) * 0.12f)
                val maxCanvasSquare = min(w, h).toFloat()
                val side = (max(rawW, rawH) + margin * 2f).coerceIn(64f, maxCanvasSquare)

                val cx = (minX + maxX) * 0.5f
                val cy = (minY + maxY) * 0.5f

                // Strictly clamp inside [0, 0, w, h] so crop region never goes outside canvas
                val left = (cx - side * 0.5f).coerceIn(0f, max(0f, w - side)).toInt()
                val top = (cy - side * 0.5f).coerceIn(0f, max(0f, h - side)).toInt()
                val right = min(w, (left + side).roundToInt())
                val bottom = min(h, (top + side).roundToInt())

                val srcRect = Rect(left, top, right, bottom)
                val dstW = right - left
                val dstH = bottom - top
                if (dstW > 0 && dstH > 0) {
                    val scale = min(thumbSize.toFloat() / dstW, thumbSize.toFloat() / dstH)
                    val rw = dstW * scale
                    val rh = dstH * scale
                    val ox = (thumbSize - rw) * 0.5f
                    val oy = (thumbSize - rh) * 0.5f
                    c.drawBitmap(bitmap, srcRect, RectF(ox, oy, ox + rw, oy + rh), thumbPaint)
                }
            }
        }

        isThumbnailDirty = false
        return thumb
    }
}

data class SketchBrushItem(
    val id: String,
    var name: String,
    var iconStyle: BrushIconStyle,
    var brushType: SketchBrushType = SketchBrushType.STANDARD,
    var size: Float = 24f,
    var opacity: Float = 100f,
    var sizeHeavyPressure: Float = 100f,
    var sizeLightPressure: Float = 25f,
    var opacityHeavyPressure: Float = 100f,
    var opacityLightPressure: Float = 40f,
    var spacing: Float = 12f,
    var roundness: Float = 100f,
    var rotation: Float = 0f,
    var hardness: Float = 85f,
    var grainStrength: Float = 0f,
    var sizeJitter: Float = 0f,
    var opacityJitter: Float = 0f,
    var scatterJitter: Float = 0f,
    val defType: SketchBrushType = brushType,
    val defSize: Float = size,
    val defOpacity: Float = opacity,
    val defHardness: Float = hardness,
    val defSpacing: Float = spacing,
    val defRoundness: Float = roundness,
    val defGrain: Float = grainStrength
) {
    fun resetToDefault() {
        brushType = defType
        size = defSize
        opacity = defOpacity
        sizeHeavyPressure = 100f
        sizeLightPressure = 25f
        opacityHeavyPressure = 100f
        opacityLightPressure = 40f
        spacing = defSpacing
        roundness = defRoundness
        rotation = 0f
        hardness = defHardness
        grainStrength = defGrain
        sizeJitter = 0f
        opacityJitter = 0f
        scatterJitter = 0f
    }
}

data class SketchBrushSet(
    val id: String,
    var name: String,
    var isPinned: Boolean = false,
    val brushes: MutableList<SketchBrushItem> = mutableListOf()
)

class ColorStudioState {
    var hue: Float = 0f
    var saturation: Float = 0.5f
    var lightness: Float = 0.0f
    var currentColor: Int = Color.BLACK
    var previousColor: Int = Color.BLACK
    var isColorlessEraser: Boolean = false
    var isEyedropperActive: Boolean = false
    var topTabIndex: Int = 0
    var bottomModeIndex: Int = 0
    var harmonyMode: Int = 0

    val colorHistory = mutableListOf(
        Color.rgb(18, 22, 238),
        Color.rgb(255, 255, 255),
        Color.rgb(0, 0, 0),
        Color.rgb(14, 150, 238),
        Color.rgb(0, 200, 255),
        Color.rgb(65, 18, 255),
        Color.rgb(235, 18, 18),
        Color.rgb(112, 112, 112),
        Color.rgb(72, 110, 0)
    )

    fun syncColorFromHsl() {
        currentColor = hslToColor(hue, saturation, lightness)
        isColorlessEraser = false
    }

    fun setFromColorInt(c: Int, recordPrevious: Boolean = false) {
        if (recordPrevious) previousColor = currentColor
        currentColor = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
        val hsl = colorToHsl(currentColor)
        hue = hsl[0]
        saturation = hsl[1]
        lightness = hsl[2]
        isColorlessEraser = false
    }

    fun pushToHistory(c: Int) {
        val opaque = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
        colorHistory.remove(opaque)
        colorHistory.add(0, opaque)
        while (colorHistory.size > 9) {
            colorHistory.removeAt(colorHistory.lastIndex)
        }
    }

    fun computeHarmonyStrip(): List<Int> {
        val out = mutableListOf<Int>()
        when (harmonyMode % 4) {
            0 -> {
                for (i in 0..5) {
                    val h = if (i < 3) hue else (hue + 180f) % 360f
                    val l = (0.25f + (i % 3) * 0.25f).coerceIn(0.08f, 0.92f)
                    out.add(hslToColor(h, saturation.coerceAtLeast(0.35f), l))
                }
            }
            1 -> {
                for (i in 0..5) {
                    val h = (hue + (i / 2) * 120f) % 360f
                    val l = if (i % 2 == 0) 0.42f else 0.68f
                    out.add(hslToColor(h, saturation.coerceAtLeast(0.5f), l))
                }
            }
            2 -> {
                for (i in 0..5) {
                    val h = (hue - 50f + i * 20f + 360f) % 360f
                    out.add(hslToColor(h, saturation.coerceAtLeast(0.45f), lightness.coerceIn(0.25f, 0.75f)))
                }
            }
            else -> {
                val angles = floatArrayOf(0f, 15f, 150f, 180f, 210f, 345f)
                for (a in angles) {
                    out.add(hslToColor((hue + a) % 360f, saturation.coerceAtLeast(0.5f), 0.52f))
                }
            }
        }
        return out
    }

    companion object {
        fun hslToColor(h: Float, s: Float, l: Float): Int {
            val c = (1f - abs(2f * l - 1f)) * s
            val x = c * (1f - abs((h / 60f) % 2f - 1f))
            val m = l - c / 2f
            val r1: Float
            val g1: Float
            val b1: Float
            when {
                h < 60f -> { r1 = c; g1 = x; b1 = 0f }
                h < 120f -> { r1 = x; g1 = c; b1 = 0f }
                h < 180f -> { r1 = 0f; g1 = c; b1 = x }
                h < 240f -> { r1 = 0f; g1 = x; b1 = c }
                h < 300f -> { r1 = x; g1 = 0f; b1 = c }
                else -> { r1 = c; g1 = 0f; b1 = x }
            }
            val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        fun colorToHslOut(color: Int, out: FloatArray) {
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val d = maxC - minC
            val l = (maxC + minC) * 0.5f
            var h = 0f
            var s = 0f
            if (d > 0.0001f) {
                s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(0.0001f)
                h = when (maxC) {
                    r -> 60f * (((g - b) / d) % 6f)
                    g -> 60f * (((b - r) / d) + 2f)
                    else -> 60f * (((r - g) / d) + 4f)
                }
                if (h < 0f) h += 360f
            }
            out[0] = h.coerceIn(0f, 360f)
            out[1] = s.coerceIn(0f, 1f)
            out[2] = l.coerceIn(0f, 1f)
        }

        fun colorToHsl(color: Int): FloatArray {
            val out = FloatArray(3)
            colorToHslOut(color, out)
            return out
        }
    }
}

object DefaultBrushCatalog {
    fun createDefaultSets(): MutableList<SketchBrushSet> {
        val basicBrushes = mutableListOf(
            SketchBrushItem("b1", "Primary Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 8f, 85f, hardness = 65f, grainStrength = 35f),
            SketchBrushItem("b2", "2B Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 12f, 90f, hardness = 60f, grainStrength = 40f),
            SketchBrushItem("b3", "4B Soft Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 18f, 92f, hardness = 55f, grainStrength = 45f),
            SketchBrushItem("b4", "6B Shading Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 26f, 80f, hardness = 45f, grainStrength = 50f),
            SketchBrushItem("b5", "HB Technical Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 5f, 95f, hardness = 85f, grainStrength = 20f),
            SketchBrushItem("b6", "Sketching Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 14f, 75f, hardness = 50f, grainStrength = 38f),
            SketchBrushItem("b7", "Drawing Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 16f, 88f, hardness = 62f, grainStrength = 30f),
            SketchBrushItem("b8", "Fine Art Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 10f, 85f, hardness = 70f, grainStrength = 28f),
            SketchBrushItem("b9", "Hard Eraser", BrushIconStyle.HARD_ERASER, SketchBrushType.ERASER, 36f, 100f, hardness = 98f),
            SketchBrushItem("b10", "Soft Eraser", BrushIconStyle.SOFT_ERASER, SketchBrushType.ERASER, 54f, 65f, hardness = 25f),
            SketchBrushItem("b11", "Chisel Marker", BrushIconStyle.CHISEL_MARKER, SketchBrushType.MARKER, 42f, 70f, roundness = 28f, rotation = -35f, hardness = 90f),
            SketchBrushItem("b12", "Fine Airbrush", BrushIconStyle.AIRBRUSH, SketchBrushType.STANDARD, 65f, 45f, hardness = 12f, spacing = 8f),
            SketchBrushItem("b13", "Soft Airbrush", BrushIconStyle.AIRBRUSH, SketchBrushType.STANDARD, 120f, 35f, hardness = 8f, spacing = 8f),
            SketchBrushItem("b14", "Technical Pen", BrushIconStyle.TECHNICAL_PEN, SketchBrushType.STANDARD, 7f, 100f, hardness = 98f, spacing = 5f),
            SketchBrushItem("b15", "Duplicate", BrushIconStyle.TECHNICAL_PEN, SketchBrushType.STANDARD, 28f, 100f, hardness = 95f, spacing = 6f),
            SketchBrushItem("b16", "Tattoo Inker", BrushIconStyle.DIAMOND_SHADER, SketchBrushType.STANDARD, 16f, 100f, hardness = 92f),
            SketchBrushItem("b17", "Fountain Pen", BrushIconStyle.FOUNTAIN_PEN, SketchBrushType.STANDARD, 15f, 100f, hardness = 96f, roundness = 45f, rotation = -30f),
            SketchBrushItem("b18", "Calligraphy Nib", BrushIconStyle.FOUNTAIN_PEN, SketchBrushType.STANDARD, 24f, 100f, hardness = 95f, roundness = 22f, rotation = -40f),
            SketchBrushItem("b19", "Watercolor Wash", BrushIconStyle.ROUND_BRUSH, SketchBrushType.MARKER, 52f, 48f, hardness = 35f),
            SketchBrushItem("b20", "Felt Tip Pen", BrushIconStyle.FELT_PEN, SketchBrushType.STANDARD, 20f, 95f, hardness = 88f),
            SketchBrushItem("b21", "Smudge Blender", BrushIconStyle.BLENDER_CONE, SketchBrushType.SMUDGE, 44f, 80f, hardness = 40f),
            SketchBrushItem("b22", "Flat Marker", BrushIconStyle.CHISEL_MARKER, SketchBrushType.MARKER, 50f, 60f, roundness = 25f, rotation = -25f),
            SketchBrushItem("b23", "Brush Marker", BrushIconStyle.CHISEL_MARKER, SketchBrushType.MARKER, 38f, 75f, roundness = 55f, rotation = -35f),
            SketchBrushItem("b24", "Pastel Block", BrushIconStyle.PASTEL_BLOCK, SketchBrushType.STANDARD, 46f, 82f, hardness = 50f, grainStrength = 65f),
            SketchBrushItem("b25", "Charcoal Stick", BrushIconStyle.CHARCOAL_STICK, SketchBrushType.STANDARD, 40f, 88f, hardness = 45f, grainStrength = 72f),
            SketchBrushItem("b26", "Synthetic Flat", BrushIconStyle.FLAT_BRISTLE, SketchBrushType.STANDARD, 48f, 90f, roundness = 35f, grainStrength = 30f),
            SketchBrushItem("b27", "Coarse Bristle", BrushIconStyle.FLAT_BRISTLE, SketchBrushType.STANDARD, 52f, 85f, grainStrength = 55f),
            SketchBrushItem("b28", "Glaze Brush", BrushIconStyle.FLAT_BRISTLE, SketchBrushType.MARKER, 64f, 40f, hardness = 50f),
            SketchBrushItem("b29", "Fan Brush", BrushIconStyle.FAN_BRUSH, SketchBrushType.STANDARD, 60f, 75f, roundness = 30f, grainStrength = 60f)
        )

        val textureBrushes = mutableListOf(
            SketchBrushItem("t1", "Textured Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 32f, 90f, hardness = 65f, grainStrength = 55f),
            SketchBrushItem("t2", "Grain Shader", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 48f, 85f, hardness = 55f, grainStrength = 70f),
            SketchBrushItem("t3", "Rough Graphite", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 36f, 88f, hardness = 60f, grainStrength = 65f),
            SketchBrushItem("t4", "Duplicate", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 180.3f, 100f, hardness = 72f, grainStrength = 62f),
            SketchBrushItem("t5", "Canvas Weave", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 54f, 82f, hardness = 50f, grainStrength = 78f),
            SketchBrushItem("t6", "Neon Fractal", BrushIconStyle.TEXTURE_FRACTAL, SketchBrushType.GLOW, 75f, 90f, hardness = 35f, grainStrength = 60f),
            SketchBrushItem("t7", "Crosshatch Stamp", BrushIconStyle.TEXTURE_HATCH, SketchBrushType.STANDARD, 68f, 85f, spacing = 22f, grainStrength = 80f),
            SketchBrushItem("t8", "Frost Crystal", BrushIconStyle.TEXTURE_FROST, SketchBrushType.STANDARD, 72f, 88f, spacing = 28f, sizeJitter = 25f, grainStrength = 75f),
            SketchBrushItem("t9", "Radial Burst", BrushIconStyle.TEXTURE_SPLATTER, SketchBrushType.STANDARD, 80f, 92f, spacing = 30f, scatterJitter = 20f, grainStrength = 70f),
            SketchBrushItem("t10", "Dark Nebula", BrushIconStyle.TEXTURE_FRACTAL, SketchBrushType.GLOW, 90f, 78f, hardness = 25f, grainStrength = 65f)
        )

        return mutableListOf(
            SketchBrushSet("set_basic", "Basic", isPinned = true, brushes = basicBrushes),
            SketchBrushSet("set_overlay", "overlay texture", isPinned = false, brushes = textureBrushes)
        )
    }
}

data class CanvasPreset(
    val id: String,
    var name: String,
    var width: Int,
    var height: Int
)

private data class LayerSnapshotEntry(
    val id: String,
    val name: String,
    val bitmapCopy: Bitmap,
    val visible: Boolean,
    val alphaLock: Boolean,
    val locked: Boolean,
    val clippingMask: Boolean,
    val opacity: Float,
    val blendMode: LayerBlendMode,
    val colorLabelIndex: Int,
    val temporalStrokesJson: String
)

private data class ProjectSnapshot(
    val selectedLayerIndex: Int,
    val backgroundColor: Int,
    val backgroundVisible: Boolean,
    val layers: List<LayerSnapshotEntry>
)

class SketchProjectState(
    var name: String = nextDefaultName(),
    var width: Int = 1080,
    var height: Int = 2408,
    var isDirty: Boolean = false,
    var isSavedOnce: Boolean = false,
    var lastSavedPath: String? = null
) {
    // Index 0 = TOP layer, layers.lastIndex = BOTTOM drawing layer (above Background)
    val layers = mutableListOf<SketchLayer>()
    var selectedLayerIndex: Int = 0
    var backgroundColor: Int = Color.WHITE
    var backgroundVisible: Boolean = true
    val backgroundConfig = BackgroundProceduralConfig()
    private var nextLayerNumber: Int = 1

    private val undoStack = ArrayDeque<ProjectSnapshot>()
    private val redoStack = ArrayDeque<ProjectSnapshot>()

    fun createBlankLayerBitmap(): Bitmap {
        val targetW = width.coerceIn(1, 8192)
        val targetH = height.coerceIn(1, 8192)
        val bmp = try {
            Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            System.gc()
            Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        }
        bmp.eraseColor(Color.TRANSPARENT)
        return bmp
    }

    fun ensureLayersInitialized() {
        if (layers.isEmpty()) {
            val firstBmp = createBlankLayerBitmap()
            layers.add(
                SketchLayer(
                    id = "layer_${System.currentTimeMillis()}_1",
                    name = "Layer 1",
                    bitmap = firstBmp
                )
            )
            nextLayerNumber = 2
            selectedLayerIndex = 0
        }
        selectedLayerIndex = selectedLayerIndex.coerceIn(0, layers.lastIndex)
    }

    fun activeLayer(): SketchLayer {
        ensureLayersInitialized()
        return layers[selectedLayerIndex]
    }

    fun ensureCanvasBitmap(): Bitmap {
        val layer = activeLayer()
        val targetW = width.coerceIn(1, 8192)
        val targetH = height.coerceIn(1, 8192)
        if (layer.bitmap.isRecycled || layer.bitmap.width != targetW || layer.bitmap.height != targetH) {
            layer.bitmap = createBlankLayerBitmap()
            layer.isThumbnailDirty = true
        }
        return layer.bitmap
    }

    fun markActiveLayerDirty() {
        if (layers.isNotEmpty() && selectedLayerIndex in layers.indices) {
            layers[selectedLayerIndex].isThumbnailDirty = true
        }
        isDirty = true
    }

    /**
     * Adds a new transparent layer on TOP (index 0) and selects it.
     */
    fun addNewLayerOnTop(): SketchLayer {
        ensureLayersInitialized()
        pushUndoSnapshot()
        val usedNums = layers.mapNotNull {
            it.name.removePrefix("Layer "). removePrefix("Layer").trim().toIntOrNull()
        }.toSet()
        while (usedNums.contains(nextLayerNumber)) {
            nextLayerNumber++
        }
        val newLayer = SketchLayer(
            id = "layer_${System.currentTimeMillis()}_$nextLayerNumber",
            name = "Layer $nextLayerNumber",
            bitmap = createBlankLayerBitmap()
        )
        nextLayerNumber++
        layers.add(0, newLayer)
        selectedLayerIndex = 0
        isDirty = true
        return newLayer
    }

    private fun captureCurrentSnapshot(): ProjectSnapshot {
        ensureLayersInitialized()
        val entries = layers.map { l ->
            LayerSnapshotEntry(
                id = l.id,
                name = l.name,
                bitmapCopy = l.bitmap.copy(Bitmap.Config.ARGB_8888, true),
                visible = l.visible,
                alphaLock = l.alphaLock,
                locked = l.locked,
                clippingMask = l.clippingMask,
                opacity = l.opacity,
                blendMode = l.blendMode,
                colorLabelIndex = l.colorLabelIndex,
                temporalStrokesJson = l.temporalStrokesJson
            )
        }
        return ProjectSnapshot(
            selectedLayerIndex = selectedLayerIndex,
            backgroundColor = backgroundColor,
            backgroundVisible = backgroundVisible,
            layers = entries
        )
    }

    private fun applySnapshot(snap: ProjectSnapshot) {
        for (l in layers) {
            if (!l.bitmap.isRecycled) l.bitmap.recycle()
            l.thumbnailBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
        layers.clear()
        for (e in snap.layers) {
            layers.add(
                SketchLayer(
                    id = e.id,
                    name = e.name,
                    bitmap = e.bitmapCopy.copy(Bitmap.Config.ARGB_8888, true),
                    visible = e.visible,
                    alphaLock = e.alphaLock,
                    locked = e.locked,
                    clippingMask = e.clippingMask,
                    opacity = e.opacity,
                    blendMode = e.blendMode,
                    colorLabelIndex = e.colorLabelIndex,
                    temporalStrokesJson = e.temporalStrokesJson
                ).apply { isThumbnailDirty = true }
            )
        }
        selectedLayerIndex = snap.selectedLayerIndex.coerceIn(0, max(0, layers.lastIndex))
        backgroundColor = snap.backgroundColor
        backgroundVisible = snap.backgroundVisible
    }

    private fun recycleSnapshot(snap: ProjectSnapshot) {
        for (e in snap.layers) {
            if (!e.bitmapCopy.isRecycled) e.bitmapCopy.recycle()
        }
    }

    fun pushUndoSnapshot() {
        ensureLayersInitialized()
        try {
            val snap = captureCurrentSnapshot()
            undoStack.addLast(snap)

            val bytesPerLayer = (width.toLong() * height.toLong() * 4L).coerceAtLeast(1L)
            val bytesPerSnap = (bytesPerLayer * max(1, layers.size)).coerceAtLeast(1L)
            val maxSteps = (180_000_000L / bytesPerSnap).toInt().coerceIn(3, 14)
            while (undoStack.size > maxSteps) {
                recycleSnapshot(undoStack.removeFirst())
            }
            for (r in redoStack) recycleSnapshot(r)
            redoStack.clear()
        } catch (_: OutOfMemoryError) {
            if (undoStack.isNotEmpty()) {
                recycleSnapshot(undoStack.removeFirst())
            }
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        try {
            redoStack.addLast(captureCurrentSnapshot())
        } catch (_: OutOfMemoryError) {}
        val prev = undoStack.removeLast()
        applySnapshot(prev)
        recycleSnapshot(prev)
        isDirty = true
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        try {
            undoStack.addLast(captureCurrentSnapshot())
        } catch (_: OutOfMemoryError) {}
        val next = redoStack.removeLast()
        applySnapshot(next)
        recycleSnapshot(next)
        isDirty = true
        return true
    }

    fun releaseAll() {
        for (l in layers) {
            if (!l.bitmap.isRecycled) l.bitmap.recycle()
            l.thumbnailBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
        layers.clear()
        for (s in undoStack) recycleSnapshot(s)
        for (s in redoStack) recycleSnapshot(s)
        undoStack.clear()
        redoStack.clear()
    }

    companion object {
        private var untitledCounter = 1

        fun nextDefaultName(): String = "Untitled-${untitledCounter++}"

        fun calculateMaxLayers(w: Int, h: Int): Int {
            if (w <= 0 || h <= 0) return 0
            val mpx = (w.toLong() * h.toLong()) / 1_000_000f
            if (mpx <= 0.01f) return 250
            val raw = (390f / mpx).roundToInt()
            return min(250, max(4, raw))
        }

        fun getHardwareScreenResolution(context: Context): Pair<Int, Int> {
            return try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.maximumWindowMetrics.bounds
                    Pair(min(b.width(), b.height()), max(b.width(), b.height()))
                } else {
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(dm)
                    Pair(min(dm.widthPixels, dm.heightPixels), max(dm.widthPixels, dm.heightPixels))
                }
            } catch (_: Exception) {
                val dm = context.resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
        }
    }
}
