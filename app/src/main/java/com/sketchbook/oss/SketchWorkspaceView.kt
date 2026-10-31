package com.sketchbook.oss

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class InlineEditField {
    NONE,
    WIDTH_BOX,
    HEIGHT_BOX,
    PRESET_NAME_BOX,
    PROJECT_NAME_BOX
}

class SketchWorkspaceView(
    context: Context,
    private val callbacks: HostCallbacks
) : View(context) {

    interface HostCallbacks {
        fun onRequestImportImage()
        fun onBeginInlineEdit(
            field: InlineEditField,
            bounds: RectF,
            initialText: String,
            numeric: Boolean,
            centered: Boolean,
            textSizePx: Float,
            textColor: Int,
            bgColor: Int
        )
        fun onEndInlineEdit()
    }

    private val uiPalette = ModUiPalette()
    val hwScreenSize: Pair<Int, Int> = SketchProjectState.getHardwareScreenResolution(context)
    var project = SketchProjectState(width = hwScreenSize.first, height = hwScreenSize.second)
    val customPresets = ProjectStorageManager.loadCustomPresets(context)
    val brushAndColorModal = BrushAndColorModal(this)
    val layerController = LayerDockAndModalsController(this)

    var popupState: TopPopupState = TopPopupState.NONE
    var newSketchTab: NewSketchTab = NewSketchTab.PLUS_CUSTOM
    var activeInlineField: InlineEditField = InlineEditField.NONE

    @Volatile
    var isSavingInProgress: Boolean = false
    @Volatile
    var savingModalTitle: String = "Saving sketch..."
    @Volatile
    var savingModalSubtitle: String = ""
    @Volatile
    var savingProgressFraction: Float = -1f
    private var savingAnimAngle: Float = 0f

    private var isCanvasEyedropperActive: Boolean = false
    private var eyedropperForBackground: Boolean = false

    var customWidthInput: Int = hwScreenSize.first
    var customHeightInput: Int = hwScreenSize.second
    var isRatioLocked: Boolean = false
    var lockedAspectRatio: Double = hwScreenSize.first.toDouble() / max(1, hwScreenSize.second).toDouble()

    var editingPresetIndex: Int = -1
    var editingPresetName: String = "Letter"

    private var presetListScrollY: Float = 0f
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var lastTouchY: Float = 0f
    private var isDraggingPreset: Boolean = false
    private var draggedPresetIndex: Int = -1
    private var dragCurrentY: Float = 0f
    private var isScrollingPresets: Boolean = false
    private var longPressRunnable: Runnable? = null

    private val userTransformMatrix = Matrix()
    private val viewMatrix = Matrix()
    private val inverseViewMatrix = Matrix()
    private var isTransformInitialized: Boolean = false
    private var isTwoFingerGesture: Boolean = false
    private var maxPointersInGesture: Int = 0
    private var prevPinchDist: Float = 0f
    private var prevPinchAngle: Float = 0f
    private var prevMidX: Float = 0f
    private var prevMidY: Float = 0f

    private data class QueuedStrokeSample(val pt: PointF, val pressure: Float)
    private val pendingStrokeSamples = mutableListOf<QueuedStrokeSample>()
    private var isDrawingStroke: Boolean = false
    private var strokeCommittedToBitmap: Boolean = false
    private var gestureDownTimeMs: Long = 0L
    private var lastCanvasPt = PointF()
    private var prevCanvasPt = PointF()
    private var smoothX = 0f
    private var smoothY = 0f
    private var lastStrokeSpeed = 0f
    private var smudgeCarryColor: Int = Color.WHITE
    private val procHistoryPts = mutableListOf<PointF>()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var statusBannerText: String? = null
    private var statusBannerExpiry: Long = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private data class HitZone(val id: String, val rect: RectF, val payload: Int = -1)
    private val hitZones = mutableListOf<HitZone>()
    private var currentPopupBounds = RectF()
    private var presetScrollAreaBounds = RectF()
    private var presetRowHeightPx = 1f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val last = ProjectStorageManager.loadLastDimensions(context, hwScreenSize.first, hwScreenSize.second)
        customWidthInput = last.first
        customHeightInput = last.second
        lockedAspectRatio = customWidthInput.toDouble() / max(1, customHeightInput).toDouble()
        project.ensureLayersInitialized()
    }

    fun resetCanvasTransform() {
        userTransformMatrix.reset()
        isTransformInitialized = true
        invalidate()
    }

    fun activateOnCanvasEyedropper(forBackground: Boolean = false) {
        isCanvasEyedropperActive = true
        eyedropperForBackground = forBackground
        showBanner("Tap anywhere on canvas to pick color")
        invalidate()
    }

    fun onInlineTextUpdatedLive(field: InlineEditField, newText: String) {
        when (field) {
            InlineEditField.WIDTH_BOX -> {
                val newW = newText.toIntOrNull()?.coerceIn(0, 16384) ?: 0
                customWidthInput = newW
                if (isRatioLocked && lockedAspectRatio > 0.0001) {
                    customHeightInput = if (newW == 0) 0 else max(1, (newW / lockedAspectRatio).roundToInt())
                }
            }
            InlineEditField.HEIGHT_BOX -> {
                val newH = newText.toIntOrNull()?.coerceIn(0, 16384) ?: 0
                customHeightInput = newH
                if (isRatioLocked && lockedAspectRatio > 0.0001) {
                    customWidthInput = if (newH == 0) 0 else max(1, (newH * lockedAspectRatio).roundToInt())
                }
            }
            InlineEditField.PRESET_NAME_BOX -> {
                editingPresetName = newText
            }
            InlineEditField.PROJECT_NAME_BOX -> {
                project.name = newText
                project.isDirty = true
            }
            InlineEditField.NONE -> {}
        }
        invalidate()
    }

    fun closeInlineEditor() {
        if (activeInlineField != InlineEditField.NONE) {
            if (activeInlineField == InlineEditField.PROJECT_NAME_BOX && project.name.isBlank()) {
                project.name = "Untitled-1"
            }
            activeInlineField = InlineEditField.NONE
            callbacks.onEndInlineEdit()
            invalidate()
        }
    }

    fun showBanner(msg: String) {
        statusBannerText = msg
        statusBannerExpiry = System.currentTimeMillis() + 2600L
        invalidate()
        postDelayed(Runnable { invalidate() }, 2700L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitZones.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sx = w / 1080f
        val topBarH = 131f * sx
        val sideFrame = 0f
        val pal = uiPalette

        // If full-screen Preferences is open, render it directly
        if (popupState == TopPopupState.PREFERENCES_SCREEN) {
            layerController.drawActiveLayerModal(canvas, w, h, popupState)
            return
        }

        // 1. Draw Workspace Backdrop
        canvas.drawColor(pal.workspaceBackdrop)

        // 2. Render Multi-Layer Composite & Apply Pivot-Locked User Transform Matrix
        val availW = w
        val availH = h - topBarH
        val compositeBmp = LayerStudioCompositor.renderProjectComposite(project, forExport = false)
        val projW = max(1, project.width).toFloat()
        val projH = max(1, project.height).toFloat()
        val fitScale = min(availW / projW, availH / projH)
        val baseDrawW = projW * fitScale
        val baseDrawH = projH * fitScale
        val baseLeft = (availW - baseDrawW) / 2f
        val baseTop = topBarH + (availH - baseDrawH) / 2f

        if (!isTransformInitialized) {
            userTransformMatrix.reset()
            isTransformInitialized = true
        }

        viewMatrix.reset()
        viewMatrix.postScale(baseDrawW / compositeBmp.width.toFloat(), baseDrawH / compositeBmp.height.toFloat())
        viewMatrix.postTranslate(baseLeft, baseTop)
        viewMatrix.postConcat(userTransformMatrix)
        viewMatrix.invert(inverseViewMatrix)

        canvas.save()
        canvas.clipRect(0f, topBarH, w, h)
        canvas.concat(viewMatrix)

        val bmpRect = RectF(0f, 0f, compositeBmp.width.toFloat(), compositeBmp.height.toFloat())
        canvas.clipRect(bmpRect)
        canvas.drawBitmap(compositeBmp, 0f, 0f, bitmapPaint)
        canvas.restore()

        // 3. Draw Right-Side Layer Dock (if open)
        layerController.drawLayerDock(canvas, w, h)

        // 4. Draw Bottom Dot (when no floating 3-circle modal is active)
        val isThreeCircleModalOpen = (
            popupState == TopPopupState.BRUSH_MENU ||
            popupState == TopPopupState.COLOR_MENU ||
            popupState == TopPopupState.BG_COLOR_MENU ||
            popupState == TopPopupState.LAYER_SETTINGS_MODAL ||
            popupState == TopPopupState.BLEND_MODE_MODAL ||
            popupState == TopPopupState.COLOR_LABEL_MODAL ||
            popupState == TopPopupState.LAYER_ADJUST_MODAL
        )
        if (!isThreeCircleModalOpen) {
            val dotY = h - 136f * sx
            val dotRadius = 15.5f * sx
            SketchIcons.drawBottomCenterDot(canvas, w / 2f, dotY, dotRadius)
        }

        // 5. Draw Top Menu Bar
        drawTopMenuBar(canvas, w, topBarH, sideFrame, pal)

        // 6. Draw Active Popup Menu or Modal
        currentPopupBounds.setEmpty()
        when (popupState) {
            TopPopupState.NONE -> {}
            TopPopupState.BUTTON1_MAIN -> drawButton1MainMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.NEW_SKETCH -> drawNewSketchMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.EDIT_PRESET -> drawEditPresetMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.SAVE_MENU -> drawSaveMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.SHARE_EXPORT_MAIN -> drawShareExportMainMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.EXPORT_AS_MENU -> drawFormatListMenu(canvas, sideFrame, topBarH, sx, "Export As", pal)
            TopPopupState.SHARE_AS_MENU -> drawFormatListMenu(canvas, sideFrame, topBarH, sx, "Share As", pal)
            TopPopupState.BRUSH_MENU -> {
                brushAndColorModal.draw(canvas, w, h, isColorMode = false, isBgColorMode = false)
                currentPopupBounds.set(brushAndColorModal.outerCardRect)
            }
            TopPopupState.COLOR_MENU -> {
                brushAndColorModal.draw(canvas, w, h, isColorMode = true, isBgColorMode = false)
                currentPopupBounds.set(brushAndColorModal.outerCardRect)
            }
            TopPopupState.BG_COLOR_MENU -> {
                brushAndColorModal.draw(canvas, w, h, isColorMode = true, isBgColorMode = true)
                currentPopupBounds.set(brushAndColorModal.outerCardRect)
            }
            TopPopupState.LAYER_SETTINGS_MODAL,
            TopPopupState.BLEND_MODE_MODAL,
            TopPopupState.COLOR_LABEL_MODAL,
            TopPopupState.LAYER_ADJUST_MODAL,
            TopPopupState.PREFERENCES_SCREEN -> {
                layerController.drawActiveLayerModal(canvas, w, h, popupState)
                currentPopupBounds.set(layerController.modalOuterRect)
            }
        }

        // 7. Non-Closable "Saving sketch..." Modal Overlay
        if (isSavingInProgress) {
            drawSavingModalOverlay(canvas, w, h, sx)
            savingAnimAngle = (savingAnimAngle + 12f) % 360f
            postInvalidateOnAnimation()
        }

        // 8. Status Banner
        val now = System.currentTimeMillis()
        val bannerMsg = statusBannerText
        if (bannerMsg != null && now < statusBannerExpiry && !isSavingInProgress) {
            textPaint.textSize = 30f * sx
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            val bw = min(w - 60f * sx, textPaint.measureText(bannerMsg) + 80f * sx)
            val bh = 78f * sx
            val bx = (w - bw) / 2f
            val by = h - 250f * sx
            bgPaint.color = Color.argb(230, 32, 35, 42)
            canvas.drawRect(bx, by, bx + bw, by + bh, bgPaint)
            borderPaint.color = pal.accentColor
            borderPaint.strokeWidth = 2f * sx
            canvas.drawRect(bx, by, bx + bw, by + bh, borderPaint)
            canvas.drawText(bannerMsg, w / 2f, by + bh * 0.64f, textPaint)
        }
    }

    private fun drawSavingModalOverlay(canvas: Canvas, w: Float, h: Float, sx: Float) {
        bgPaint.color = Color.argb(145, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val cardW = 650f * sx
        val cardH = if (savingProgressFraction >= 0f) 275f * sx else 240f * sx
        val cardRect = RectF((w - cardW) * 0.5f, (h - cardH) * 0.5f, (w + cardW) * 0.5f, (h + cardH) * 0.5f)
        bgPaint.color = Color.rgb(245, 245, 245)
        canvas.drawRect(cardRect, bgPaint)
        borderPaint.color = Color.rgb(55, 116, 205)
        borderPaint.strokeWidth = 3f * sx
        canvas.drawRect(cardRect, borderPaint)

        val spinCx = cardRect.left + 96f * sx
        val spinCy = cardRect.top + 118f * sx
        val spinR = 40f * sx
        borderPaint.color = Color.rgb(210, 210, 210)
        borderPaint.strokeWidth = 8f * sx
        canvas.drawCircle(spinCx, spinCy, spinR, borderPaint)
        borderPaint.color = Color.rgb(55, 116, 205)
        canvas.drawArc(RectF(spinCx - spinR, spinCy - spinR, spinCx + spinR, spinCy + spinR), savingAnimAngle, 110f, false, borderPaint)

        val textLeft = cardRect.left + 166f * sx
        val maxTextW = cardRect.right - textLeft - 28f * sx

        textPaint.color = Color.rgb(30, 30, 30)
        textPaint.textSize = 35f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        canvas.drawText(ellipsize(savingModalTitle, textPaint, maxTextW), textLeft, spinCy - 6f * sx, textPaint)
        textPaint.isFakeBoldText = false

        val subStr = savingModalSubtitle.ifBlank { "${project.width} × ${project.height} px" }
        subTextPaint.color = Color.rgb(105, 105, 105)
        subTextPaint.textSize = 25f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(ellipsize(subStr, subTextPaint, maxTextW), textLeft, spinCy + 38f * sx, subTextPaint)
    }

    private fun drawTopMenuBar(canvas: Canvas, w: Float, topBarH: Float, sideFrame: Float, pal: ModUiPalette) {
        bgPaint.style = Paint.Style.FILL
        bgPaint.color = pal.topBarBg
        canvas.drawRect(sideFrame, 0f, w - sideFrame, topBarH, bgPaint)

        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f
        canvas.drawLine(sideFrame, topBarH, w - sideFrame, topBarH, borderPaint)

        val innerW = w - sideFrame * 2f
        val slotW = innerW / 8f
        val cy = topBarH / 2f

        if (popupState in setOf(
                TopPopupState.BUTTON1_MAIN, TopPopupState.NEW_SKETCH, TopPopupState.EDIT_PRESET,
                TopPopupState.SAVE_MENU, TopPopupState.SHARE_EXPORT_MAIN, TopPopupState.EXPORT_AS_MENU, TopPopupState.SHARE_AS_MENU
            )
        ) {
            bgPaint.color = pal.topBarActiveSlot
            canvas.drawRect(sideFrame, 0f, sideFrame + slotW, topBarH, bgPaint)
        }

        for (i in 0 until 8) {
            val slotLeft = sideFrame + slotW * i
            val slotRect = RectF(slotLeft, 0f, slotLeft + slotW, topBarH)
            val cx = slotRect.centerX()
            when (i) {
                0 -> {
                    SketchIcons.drawTopIcon1Menu(canvas, cx, cy, topBarH, pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_1", slotRect))
                }
                1 -> {
                    SketchIcons.drawTopIconUndoRedo(canvas, cx, cy, topBarH, isRedo = false, enabled = project.canUndo(), tint = pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_UNDO", slotRect))
                }
                2 -> {
                    SketchIcons.drawTopIconUndoRedo(canvas, cx, cy, topBarH, isRedo = true, enabled = project.canRedo(), tint = pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_REDO", slotRect))
                }
                3 -> SketchIcons.drawTopIcon4Grid(canvas, cx, cy, topBarH, pal.iconTint)
                4 -> {
                    SketchIcons.drawTopIcon5PencilPen(canvas, cx, cy, topBarH, pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_BRUSH", slotRect))
                }
                5 -> {
                    SketchIcons.drawTopIcon6ColorWheel(canvas, cx, cy, topBarH)
                    hitZones.add(HitZone("TOP_BTN_COLOR", slotRect))
                }
                6 -> {
                    SketchIcons.drawTopIcon7Layers(canvas, cx, cy, topBarH, pal.iconTint, pal.topBarBg)
                    hitZones.add(HitZone("TOP_BTN_LAYERS", slotRect))
                }
                7 -> {
                    SketchIcons.drawTopIcon8Viewfinder(canvas, cx, cy, topBarH, pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_FIT_VIEW", slotRect))
                }
            }
        }
    }

    private fun drawButton1MainMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val rowH = 132f * sx
        val totalRows = 6
        val panelH = rowH * totalRows
        val right = left + panelW
        val bottom = top + panelH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)
        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(currentPopupBounds, borderPaint)

        val r0Top = top
        val r0Bot = r0Top + rowH
        bgPaint.color = pal.popupHeaderBg
        canvas.drawRect(left, r0Top, right, r0Bot, bgPaint)
        SketchIcons.drawProjectNameIcon(canvas, left + 58f * sx, r0Top + rowH / 2f, 44f * sx, pal.iconTint)

        val nameBoxRect = RectF(left + 118f * sx, r0Top + 22f * sx, right - 28f * sx, r0Bot - 22f * sx)
        bgPaint.color = pal.inputBg
        canvas.drawRect(nameBoxRect, bgPaint)
        borderPaint.color = if (activeInlineField == InlineEditField.PROJECT_NAME_BOX) pal.accentColor else pal.borderColor
        borderPaint.strokeWidth = 2.4f * sx
        canvas.drawRect(nameBoxRect, borderPaint)

        if (activeInlineField != InlineEditField.PROJECT_NAME_BOX) {
            textPaint.color = pal.textPrimary
            textPaint.textSize = 36f * sx
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            canvas.drawText(
                ellipsize(project.name, textPaint, nameBoxRect.width() - 36f * sx),
                nameBoxRect.left + 18f * sx,
                nameBoxRect.centerY() + 13f * sx,
                textPaint
            )
        }
        hitZones.add(HitZone("EDIT_PROJECT_NAME", nameBoxRect))

        borderPaint.color = pal.borderColor
        canvas.drawLine(left, r0Bot, right, r0Bot, borderPaint)

        data class MenuItem(val id: String, val label: String, val clickable: Boolean)
        val items = listOf(
            MenuItem("MENU_NEW_SKETCH", "New Sketch", true),
            MenuItem("MENU_SAVE", "Save", true),
            MenuItem("MENU_IMPORT", "Import from Files", true),
            MenuItem("MENU_PREFS", "Preferences", true),
            MenuItem("MENU_SHARE", "Share / Export", true)
        )

        for ((idx, item) in items.withIndex()) {
            val ry = r0Bot + idx * rowH
            val cy = ry + rowH / 2f
            val iconX = left + 58f * sx
            val iconSize = 46f * sx

            when (item.id) {
                "MENU_NEW_SKETCH" -> SketchIcons.drawPlusIcon(canvas, iconX, cy, iconSize, pal.iconTint)
                "MENU_SAVE" -> SketchIcons.drawSaveIcon(canvas, iconX, cy, iconSize, pal.iconTint)
                "MENU_IMPORT" -> SketchIcons.drawImportIcon(canvas, iconX, cy, iconSize, pal.iconTint)
                "MENU_PREFS" -> SketchIcons.drawGearIcon(canvas, iconX, cy, iconSize, filled = true, color = pal.iconTint)
                "MENU_SHARE" -> SketchIcons.drawShareIcon(canvas, iconX, cy, iconSize, withStar = true, color = pal.iconTint)
            }

            textPaint.color = pal.textPrimary
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            canvas.drawText(item.label, left + 138f * sx, cy + 14f * sx, textPaint)

            if (item.clickable) {
                hitZones.add(HitZone(item.id, RectF(left, ry, right, ry + rowH)))
            }
        }
    }

    private fun drawNewSketchMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val headerH = 132f * sx
        val tabH = 116f * sx
        val bodyH = if (newSketchTab == NewSketchTab.PLUS_CUSTOM) 1040f * sx else 1170f * sx
        val right = left + panelW
        val bottom = top + headerH + tabH + bodyH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, headerH, sx, "New Sketch", "BACK_TO_MAIN", pal)

        val tabTop = top + headerH
        val tabMid = left + panelW / 2f
        val tabBot = tabTop + tabH

        val leftTabRect = RectF(left, tabTop, tabMid, tabBot)
        val rightTabRect = RectF(tabMid, tabTop, right, tabBot)

        bgPaint.color = if (newSketchTab == NewSketchTab.PLUS_CUSTOM) Color.rgb(142, 142, 142) else pal.popupHeaderBg
        canvas.drawRect(leftTabRect, bgPaint)

        bgPaint.color = if (newSketchTab == NewSketchTab.LIST_PRESETS) Color.rgb(142, 142, 142) else pal.popupHeaderBg
        canvas.drawRect(rightTabRect, bgPaint)

        SketchIcons.drawPlusIcon(canvas, leftTabRect.centerX(), leftTabRect.centerY(), 46f * sx, pal.textPrimary)
        SketchIcons.drawListBarsIcon(canvas, rightTabRect.centerX(), rightTabRect.centerY(), 46f * sx, pal.textPrimary)

        hitZones.add(HitZone("TAB_PLUS", leftTabRect))
        hitZones.add(HitZone("TAB_LIST", rightTabRect))

        if (newSketchTab == NewSketchTab.PLUS_CUSTOM) {
            drawCustomDimensionBody(
                canvas = canvas,
                left = left,
                bodyTop = tabBot,
                panelW = panelW,
                bodyH = bodyH,
                sx = sx,
                buttonLabel = "Create",
                buttonZoneId = "BTN_CREATE_CUSTOM",
                showPresetNameBox = false,
                showDeleteButton = false,
                pal = pal
            )
        } else {
            drawPresetsListTab(canvas, left, tabBot, panelW, bodyH, sx, pal)
        }
    }

    private fun drawCustomDimensionBody(
        canvas: Canvas,
        left: Float,
        bodyTop: Float,
        panelW: Float,
        bodyH: Float,
        sx: Float,
        buttonLabel: String,
        buttonZoneId: String,
        showPresetNameBox: Boolean,
        showDeleteButton: Boolean,
        pal: ModUiPalette
    ) {
        val right = left + panelW
        var cursorY = bodyTop + 28f * sx

        if (showPresetNameBox) {
            val nameBoxW = 492f * sx
            val nameBoxH = 78f * sx
            val nx = left + (panelW - nameBoxW) / 2f
            val nameRect = RectF(nx, cursorY, nx + nameBoxW, cursorY + nameBoxH)
            bgPaint.color = pal.inputBg
            canvas.drawRect(nameRect, bgPaint)
            borderPaint.color = if (activeInlineField == InlineEditField.PRESET_NAME_BOX) pal.accentColor else pal.borderColor
            borderPaint.strokeWidth = 2.2f * sx
            canvas.drawRect(nameRect, borderPaint)

            if (activeInlineField != InlineEditField.PRESET_NAME_BOX) {
                textPaint.color = pal.textPrimary
                textPaint.textSize = 37f * sx
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(editingPresetName, nameRect.centerX(), nameRect.centerY() + 13f * sx, textPaint)
            }
            hitZones.add(HitZone("EDIT_PRESET_NAME_FIELD", nameRect))

            cursorY += nameBoxH + 24f * sx
        }

        val isZeroDim = customWidthInput <= 0 || customHeightInput <= 0
        val mpxVal = if (isZeroDim) 0f else (customWidthInput.toLong() * customHeightInput.toLong()) / 1_000_000f
        val maxLayers = SketchProjectState.calculateMaxLayers(customWidthInput, customHeightInput)

        subTextPaint.textAlign = Paint.Align.RIGHT
        subTextPaint.textSize = 29f * sx
        subTextPaint.color = pal.textSecondary
        subTextPaint.isFakeBoldText = false
        canvas.drawText(String.format("%.2fMpx", mpxVal), right - 26f * sx, cursorY + 24f * sx, subTextPaint)

        subTextPaint.textSize = 31f * sx
        subTextPaint.isFakeBoldText = true
        subTextPaint.color = if (isZeroDim) Color.rgb(190, 55, 55) else pal.textPrimary
        canvas.drawText("L$maxLayers", right - 26f * sx, cursorY + 66f * sx, subTextPaint)
        subTextPaint.isFakeBoldText = false

        val previewAreaTop = cursorY + 36f * sx
        val previewMaxBox = 300f * sx
        val previewCx = left + panelW / 2f
        val previewCy = previewAreaTop + previewMaxBox / 2f

        if (isZeroDim) {
            val zeroW = if (customWidthInput <= 0) 90f * sx else 220f * sx
            val zeroH = if (customHeightInput <= 0) 90f * sx else 220f * sx
            val zRect = RectF(previewCx - zeroW / 2f, previewCy - zeroH / 2f, previewCx + zeroW / 2f, previewCy + zeroH / 2f)

            bgPaint.color = Color.rgb(232, 226, 226)
            canvas.drawRect(zRect, bgPaint)

            borderPaint.color = Color.rgb(185, 70, 70)
            borderPaint.strokeWidth = 2.5f * sx
            borderPaint.pathEffect = DashPathEffect(floatArrayOf(12f * sx, 8f * sx), 0f)
            canvas.drawRect(zRect, borderPaint)
            canvas.drawLine(zRect.left, zRect.top, zRect.right, zRect.bottom, borderPaint)
            canvas.drawLine(zRect.right, zRect.top, zRect.left, zRect.bottom, borderPaint)
            borderPaint.pathEffect = null

            subTextPaint.textAlign = Paint.Align.CENTER
            subTextPaint.color = Color.rgb(175, 50, 50)
            subTextPaint.textSize = 26f * sx
            canvas.drawText("${customWidthInput} × ${customHeightInput}", previewCx, zRect.bottom + 36f * sx, subTextPaint)
        } else {
            val ratio = customWidthInput.toFloat() / customHeightInput.toFloat()
            val cardW: Float
            val cardH: Float
            if (ratio >= 1f) {
                cardW = min(previewMaxBox, previewMaxBox * 0.92f)
                cardH = (cardW / ratio).coerceIn(48f * sx, previewMaxBox)
            } else {
                cardH = min(previewMaxBox, previewMaxBox * 0.92f)
                cardW = (cardH * ratio).coerceIn(48f * sx, previewMaxBox)
            }
            val cardRect = RectF(previewCx - cardW / 2f, previewCy - cardH / 2f, previewCx + cardW / 2f, previewCy + cardH / 2f)

            shadowPaint.color = Color.argb(42, 0, 0, 0)
            canvas.drawRect(cardRect.left + 4f * sx, cardRect.top + 8f * sx, cardRect.right + 8f * sx, cardRect.bottom + 12f * sx, shadowPaint)

            bgPaint.color = pal.cardBg
            canvas.drawRect(cardRect, bgPaint)
            borderPaint.color = pal.borderColor
            borderPaint.strokeWidth = 2f * sx
            canvas.drawRect(cardRect, borderPaint)

            SketchIcons.drawCanvasRotateIcon(canvas, previewCx, previewCy, 56f * sx, pal.iconTint)
            hitZones.add(HitZone("ROTATE_CANVAS_PREVIEW", cardRect))
        }

        val boxesY = previewAreaTop + previewMaxBox + 84f * sx
        val boxW = 216f * sx
        val boxH = 82f * sx
        val leftBoxX = left + 48f * sx
        val rightBoxX = right - 48f * sx - boxW

        val widthBoxRect = RectF(leftBoxX, boxesY, leftBoxX + boxW, boxesY + boxH)
        val heightBoxRect = RectF(rightBoxX, boxesY, rightBoxX + boxW, boxesY + boxH)
        val linkIconRect = RectF(widthBoxRect.right, boxesY, heightBoxRect.left, boxesY + boxH)

        bgPaint.color = pal.inputBg
        canvas.drawRect(widthBoxRect, bgPaint)
        borderPaint.color = when {
            activeInlineField == InlineEditField.WIDTH_BOX -> pal.accentColor
            customWidthInput <= 0 -> Color.rgb(195, 55, 55)
            else -> Color.rgb(117, 117, 117)
        }
        borderPaint.strokeWidth = if (activeInlineField == InlineEditField.WIDTH_BOX) 3.2f * sx else 2.4f * sx
        canvas.drawRect(widthBoxRect, borderPaint)

        if (activeInlineField != InlineEditField.WIDTH_BOX) {
            textPaint.color = pal.textPrimary
            textPaint.textSize = 37f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(customWidthInput.toString(), widthBoxRect.centerX(), widthBoxRect.centerY() + 13f * sx, textPaint)
        }
        hitZones.add(HitZone("INPUT_WIDTH_BOX", widthBoxRect))

        bgPaint.color = pal.inputBg
        canvas.drawRect(heightBoxRect, bgPaint)
        borderPaint.color = when {
            activeInlineField == InlineEditField.HEIGHT_BOX -> pal.accentColor
            customHeightInput <= 0 -> Color.rgb(195, 55, 55)
            else -> Color.rgb(117, 117, 117)
        }
        borderPaint.strokeWidth = if (activeInlineField == InlineEditField.HEIGHT_BOX) 3.2f * sx else 2.4f * sx
        canvas.drawRect(heightBoxRect, borderPaint)

        if (activeInlineField != InlineEditField.HEIGHT_BOX) {
            textPaint.color = pal.textPrimary
            textPaint.textSize = 37f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(customHeightInput.toString(), heightBoxRect.centerX(), heightBoxRect.centerY() + 13f * sx, textPaint)
        }
        hitZones.add(HitZone("INPUT_HEIGHT_BOX", heightBoxRect))

        SketchIcons.drawLinkRatioIcon(canvas, linkIconRect.centerX(), linkIconRect.centerY(), 50f * sx, isRatioLocked, pal.accentColor, pal.iconTint)
        hitZones.add(HitZone("TOGGLE_RATIO_LOCK", linkIconRect))

        val createBtnW = 376f * sx
        val createBtnH = 88f * sx
        val createBtnX = left + (panelW - createBtnW) / 2f
        val createBtnY = bodyTop + bodyH - createBtnH - 46f * sx

        if (showDeleteButton && editingPresetIndex in customPresets.indices) {
            val delW = 376f * sx
            val delH = 72f * sx
            val delX = left + (panelW - delW) / 2f
            val delY = createBtnY - delH - 24f * sx
            val delRect = RectF(delX, delY, delX + delW, delY + delH)

            bgPaint.color = Color.rgb(235, 225, 225)
            canvas.drawRect(delRect, bgPaint)
            borderPaint.color = Color.rgb(185, 65, 65)
            borderPaint.strokeWidth = 2f * sx
            canvas.drawRect(delRect, borderPaint)

            SketchIcons.drawTrashIcon(canvas, delRect.left + 48f * sx, delRect.centerY(), 38f * sx)
            textPaint.color = Color.rgb(180, 45, 45)
            textPaint.textSize = 32f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Delete Preset", delRect.centerX() + 18f * sx, delRect.centerY() + 11f * sx, textPaint)
            hitZones.add(HitZone("BTN_DELETE_PRESET", delRect))
        }

        val createRect = RectF(createBtnX, createBtnY, createBtnX + createBtnW, createBtnY + createBtnH)
        bgPaint.color = if (isZeroDim) Color.rgb(165, 180, 205) else pal.accentColor
        canvas.drawRect(createRect, bgPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 39f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(buttonLabel, createRect.centerX(), createRect.centerY() + 14f * sx, textPaint)
        if (!isZeroDim) {
            hitZones.add(HitZone(buttonZoneId, createRect))
        }
    }

    private fun drawPresetsListTab(canvas: Canvas, left: Float, bodyTop: Float, panelW: Float, bodyH: Float, sx: Float, pal: ModUiPalette) {
        val right = left + panelW
        val pinnedRowH = 166f * sx
        val addBtnH = 112f * sx

        val screenTop = bodyTop
        val screenBot = screenTop + pinnedRowH
        bgPaint.color = pal.cardBg
        canvas.drawRect(left, screenTop, right, screenBot, bgPaint)

        textPaint.color = pal.textSecondary
        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        canvas.drawText("Screen", left + 225f * sx, screenTop + 62f * sx, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = 33f * sx
        canvas.drawText("${hwScreenSize.first} * ${hwScreenSize.second}", left + 225f * sx, screenTop + 132f * sx, textPaint)
        SketchIcons.drawPhoneScreenIcon(canvas, left + 530f * sx, screenTop + pinnedRowH / 2f, 84f * sx, pal.iconTint)
        hitZones.add(HitZone("PRESET_SCREEN", RectF(left, screenTop, right, screenBot)))

        val lastDims = ProjectStorageManager.loadLastDimensions(context, hwScreenSize.first, hwScreenSize.second)
        val lastTop = screenBot
        val lastBot = lastTop + pinnedRowH
        canvas.drawRect(left, lastTop, right, lastBot, bgPaint)

        textPaint.isFakeBoldText = true
        textPaint.textSize = 37f * sx
        canvas.drawText("Last", left + 225f * sx, lastTop + 62f * sx, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = 33f * sx
        canvas.drawText("${lastDims.first} * ${lastDims.second}", left + 225f * sx, lastTop + 132f * sx, textPaint)
        SketchIcons.drawLastCanvasIcon(canvas, left + 530f * sx, lastTop + pinnedRowH / 2f, 84f * sx, pal.iconTint)
        hitZones.add(HitZone("PRESET_LAST", RectF(left, lastTop, right, lastBot)))

        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawLine(left, lastBot, right, lastBot, borderPaint)

        val listTop = lastBot
        val listBot = bodyTop + bodyH - addBtnH
        presetScrollAreaBounds.set(left, listTop, right, listBot)

        val rowH = 160f * sx
        presetRowHeightPx = rowH

        val totalRows = customPresets.size
        val contentH = totalRows * rowH
        val maxScroll = max(0f, contentH - presetScrollAreaBounds.height())
        presetListScrollY = presetListScrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(presetScrollAreaBounds)

        for (i in customPresets.indices) {
            if (isDraggingPreset && i == draggedPresetIndex) continue
            val itemTop = listTop + i * rowH - presetListScrollY
            if (itemTop + rowH >= listTop && itemTop <= listBot) {
                drawSingleCustomPresetRow(canvas, customPresets[i], i, left, itemTop, panelW, rowH, sx, isFloating = false, pal = pal)
            }
        }

        if (isDraggingPreset && draggedPresetIndex in customPresets.indices) {
            val floatTop = (dragCurrentY - rowH / 2f).coerceIn(listTop, listBot - rowH)
            drawSingleCustomPresetRow(
                canvas,
                customPresets[draggedPresetIndex],
                draggedPresetIndex,
                left,
                floatTop,
                panelW,
                rowH,
                sx,
                isFloating = true,
                pal = pal
            )
        }

        canvas.restore()

        val addTop = listBot
        val addBot = bodyTop + bodyH
        val addRect = RectF(left, addTop, right, addBot)
        bgPaint.color = pal.popupBg
        canvas.drawRect(addRect, bgPaint)
        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawLine(left, addTop, right, addTop, borderPaint)

        textPaint.color = pal.textPrimary
        textPaint.textSize = 38f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Add Preset", addRect.centerX(), addRect.centerY() + 14f * sx, textPaint)
        hitZones.add(HitZone("BTN_ADD_PRESET", addRect))
    }

    private fun drawSingleCustomPresetRow(
        canvas: Canvas,
        preset: CanvasPreset,
        index: Int,
        left: Float,
        rowTop: Float,
        panelW: Float,
        rowH: Float,
        sx: Float,
        isFloating: Boolean,
        pal: ModUiPalette
    ) {
        val right = left + panelW
        if (isFloating) {
            shadowPaint.color = Color.argb(60, 0, 0, 0)
            canvas.drawRect(left, rowTop - 4f * sx, right, rowTop + rowH + 6f * sx, shadowPaint)
            bgPaint.color = pal.topBarActiveSlot
            canvas.drawRect(left, rowTop, right, rowTop + rowH, bgPaint)
        } else {
            bgPaint.color = pal.popupBg
            canvas.drawRect(left, rowTop, right, rowTop + rowH, bgPaint)
        }

        val cardLeft = left + 26f * sx
        val cardRight = left + 448f * sx
        val stripTop = rowTop + 22f * sx
        val stripBot = rowTop + 82f * sx
        bgPaint.color = pal.cardBg
        canvas.drawRect(cardLeft, stripTop, cardRight, stripBot, bgPaint)

        textPaint.color = pal.textSecondary
        textPaint.textSize = 37f * sx
        textPaint.isFakeBoldText = true
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(preset.name, (cardLeft + cardRight) / 2f, stripTop + 44f * sx, textPaint)

        textPaint.isFakeBoldText = false
        textPaint.textSize = 36f * sx
        canvas.drawText("${preset.width} * ${preset.height}", (cardLeft + cardRight) / 2f, rowTop + 132f * sx, textPaint)

        if (index >= 0) {
            val gearX = left + 494f * sx
            val reorderX = left + 575f * sx
            val iconCy = rowTop + 54f * sx
            SketchIcons.drawGearIcon(canvas, gearX, iconCy, 38f * sx, filled = false, color = pal.iconTint)
            SketchIcons.drawReorderIcon(canvas, reorderX, iconCy, 38f * sx, active = isFloating, tint = pal.iconTint)

            if (!isFloating) {
                hitZones.add(HitZone("PRESET_SELECT", RectF(left, rowTop, left + 455f * sx, rowTop + rowH), index))
                hitZones.add(HitZone("PRESET_GEAR", RectF(left + 455f * sx, rowTop, left + 535f * sx, rowTop + rowH), index))
                hitZones.add(HitZone("PRESET_REORDER", RectF(left + 535f * sx, rowTop, right, rowTop + rowH), index))
            }
        }
    }

    private fun drawEditPresetMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val headerH = 132f * sx
        val bodyH = 1190f * sx
        val right = left + panelW
        val bottom = top + headerH + bodyH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        val headerTitle = if (editingPresetIndex >= 0) "Edit Preset" else "Add Preset"
        drawPopupHeader(canvas, left, top, panelW, headerH, sx, headerTitle, "BACK_TO_PRESET_LIST", pal)

        drawCustomDimensionBody(
            canvas = canvas,
            left = left,
            bodyTop = top + headerH,
            panelW = panelW,
            bodyH = bodyH,
            sx = sx,
            buttonLabel = "DONE",
            buttonZoneId = "BTN_DONE_EDIT_PRESET",
            showPresetNameBox = true,
            showDeleteButton = (editingPresetIndex >= 0),
            pal = pal
        )
    }

    private fun drawSaveMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val headerH = 132f * sx
        val nameRowH = 114f * sx
        val itemRowH = 132f * sx
        val staticRows = if (project.isSavedOnce) 2 else 1
        val totalH = headerH + nameRowH + itemRowH * staticRows
        val right = left + panelW
        val bottom = top + totalH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)
        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(currentPopupBounds, borderPaint)

        drawPopupHeader(canvas, left, top, panelW, headerH, sx, "Save", "BACK_TO_MAIN", pal)

        val nameTop = top + headerH
        val nameBot = nameTop + nameRowH
        bgPaint.color = pal.popupHeaderBg
        canvas.drawRect(left, nameTop, right, nameBot, bgPaint)

        subTextPaint.color = pal.textSecondary
        subTextPaint.textSize = 28f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Name:", left + 28f * sx, nameTop + nameRowH / 2f + 10f * sx, subTextPaint)

        val fileBox = RectF(left + 132f * sx, nameTop + 18f * sx, right - 26f * sx, nameBot - 18f * sx)
        bgPaint.color = pal.inputBg
        canvas.drawRect(fileBox, bgPaint)
        borderPaint.color = if (activeInlineField == InlineEditField.PROJECT_NAME_BOX) pal.accentColor else pal.borderColor
        borderPaint.strokeWidth = 2.4f * sx
        canvas.drawRect(fileBox, borderPaint)

        if (activeInlineField != InlineEditField.PROJECT_NAME_BOX) {
            textPaint.color = pal.textPrimary
            textPaint.textSize = 34f * sx
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            canvas.drawText(
                ellipsize(project.name, textPaint, fileBox.width() - 30f * sx),
                fileBox.left + 16f * sx,
                fileBox.centerY() + 12f * sx,
                textPaint
            )
        }
        hitZones.add(HitZone("EDIT_PROJECT_NAME", fileBox))

        borderPaint.color = pal.borderColor
        canvas.drawLine(left, nameBot, right, nameBot, borderPaint)

        val maxRowTextW = panelW - 156f * sx
        var curY = nameBot

        val r1Top = curY
        val r1Bot = r1Top + itemRowH
        SketchIcons.drawPictureSaveIcon(canvas, left + 58f * sx, r1Top + itemRowH / 2f, 52f * sx, isCopy = false, color = pal.iconTint, bgMask = pal.popupBg)
        textPaint.color = pal.textPrimary
        textPaint.textSize = 36f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        canvas.drawText(ellipsize("Save current sketch", textPaint, maxRowTextW), left + 132f * sx, r1Top + itemRowH / 2f + 13f * sx, textPaint)
        hitZones.add(HitZone("ACTION_SAVE_CURRENT", RectF(left, r1Top, right, r1Bot)))
        curY = r1Bot

        if (project.isSavedOnce) {
            val r2Top = curY
            val r2Bot = r2Top + itemRowH
            borderPaint.color = pal.borderColor
            canvas.drawLine(left, r2Top, right, r2Top, borderPaint)

            SketchIcons.drawPictureSaveIcon(canvas, left + 58f * sx, r2Top + itemRowH / 2f, 52f * sx, isCopy = true, color = pal.iconTint, bgMask = pal.popupBg)
            textPaint.color = pal.textPrimary
            textPaint.textSize = 36f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(ellipsize("Save current sketch as a copy", textPaint, maxRowTextW), left + 132f * sx, r2Top + itemRowH / 2f + 13f * sx, textPaint)
            hitZones.add(HitZone("ACTION_SAVE_COPY", RectF(left, r2Top, right, r2Bot)))
        }
    }

    private fun drawShareExportMainMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val rowH = 132f * sx
        val right = left + panelW
        val bottom = top + rowH * 3
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, rowH, sx, "Share / Export", "BACK_TO_MAIN", pal)

        val r1Top = top + rowH
        val r1Bot = r1Top + rowH
        SketchIcons.drawFolderIcon(canvas, left + 58f * sx, r1Top + rowH / 2f, 52f * sx, pal.iconTint)
        textPaint.color = pal.textPrimary
        textPaint.textSize = 39f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Export to Files", left + 132f * sx, r1Top + rowH / 2f + 14f * sx, textPaint)
        hitZones.add(HitZone("OPEN_EXPORT_AS", RectF(left, r1Top, right, r1Bot)))

        borderPaint.color = pal.borderColor
        canvas.drawLine(left, r1Bot, right, r1Bot, borderPaint)

        val r2Top = r1Bot
        val r2Bot = r2Top + rowH
        SketchIcons.drawShareIcon(canvas, left + 58f * sx, r2Top + rowH / 2f, 48f * sx, withStar = false, color = pal.iconTint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Share", left + 132f * sx, r2Top + rowH / 2f + 14f * sx, textPaint)
        hitZones.add(HitZone("OPEN_SHARE_AS", RectF(left, r2Top, right, r2Bot)))
    }

    private fun drawFormatListMenu(canvas: Canvas, left: Float, top: Float, sx: Float, title: String, pal: ModUiPalette) {
        val panelW = 622f * sx
        val rowH = 132f * sx
        val formats = listOf(
            Triple("TIF", "Sketchbook TIFF", true),
            Triple("PSD", "PSD", true),
            Triple("PNG", "PNG", false),
            Triple("JPG", "JPG", false),
            Triple("PDF", "PDF", false)
        )
        val right = left + panelW
        val bottom = top + rowH * (1 + formats.size)
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, rowH, sx, title, "BACK_TO_SHARE_MAIN", pal)

        val curY = top + rowH

        for ((idx, fmt) in formats.withIndex()) {
            val ry = curY + rowH * idx
            val cy = ry + rowH / 2f
            SketchIcons.drawFileFormatIcon(
                c = canvas,
                cx = left + 76f * sx,
                cy = cy,
                size = 58f * sx,
                label = fmt.first,
                stacked = fmt.third,
                starred = (fmt.first == "PDF"),
                color = pal.iconTint,
                bgMask = pal.popupBg
            )
            textPaint.color = pal.textPrimary
            textPaint.textSize = 36f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(ellipsize(fmt.second, textPaint, panelW - 196f * sx), left + 166f * sx, cy + 13f * sx, textPaint)

            hitZones.add(HitZone("EXPORT_FMT_${fmt.first}", RectF(left, ry, right, ry + rowH)))

            if (idx < formats.size - 1) {
                borderPaint.color = pal.borderColor
                canvas.drawLine(left, ry + rowH, right, ry + rowH, borderPaint)
            }
        }
    }

    private fun drawPopupHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        panelW: Float,
        headerH: Float,
        sx: Float,
        title: String,
        backZoneId: String,
        pal: ModUiPalette
    ) {
        val right = left + panelW
        val bot = top + headerH
        bgPaint.color = pal.popupHeaderBg
        canvas.drawRect(left, top, right, bot, bgPaint)

        SketchIcons.drawBackChevron(canvas, left + 56f * sx, top + headerH / 2f, 48f * sx, pal.iconTint)
        hitZones.add(HitZone(backZoneId, RectF(left, top, left + 135f * sx, bot)))

        textPaint.color = pal.textPrimary
        textPaint.textSize = 40f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = false
        canvas.drawText(title, left + panelW * 0.48f, top + headerH / 2f + 14f * sx, textPaint)

        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawLine(left, bot, right, bot, borderPaint)
    }

    // ==========================================================================
    // TOUCH ENGINE: NON-CLOSABLE SAVING LOCK + PINNABLE LAYER DOCK + MODALS
    // ==========================================================================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isSavingInProgress) return true

        val x = event.x
        val y = event.y
        val sx = width / 1080f
        val topBarH = 131f * sx
        maxPointersInGesture = max(maxPointersInGesture, event.pointerCount)

        // 1. Full-Screen Preferences
        if (popupState == TopPopupState.PREFERENCES_SCREEN) {
            return layerController.onModalTouchEvent(event, popupState)
        }

        // 2. Layer Modals (Layer Settings, Blend Mode, Color Label, Layer Adjust)
        if (popupState == TopPopupState.LAYER_SETTINGS_MODAL ||
            popupState == TopPopupState.BLEND_MODE_MODAL ||
            popupState == TopPopupState.COLOR_LABEL_MODAL ||
            popupState == TopPopupState.LAYER_ADJUST_MODAL
        ) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!layerController.isTouchInsideModalOrBottomCircles(x, y, width.toFloat(), height.toFloat())) {
                    layerController.cleanupAdjustBackup(commit = true)
                    popupState = TopPopupState.NONE
                    // If user also tapped outside the Layer Dock while unpinned, close the Layer Dock too!
                    if (layerController.isLayerDockOpen && !layerController.isLayerDockPinned && !layerController.isTouchInsideLayerDock(x, y) && y > topBarH) {
                        layerController.isLayerDockOpen = false
                    }
                    invalidate()
                    // Fall through so drawing or dock/topbar tap executes immediately!
                } else {
                    return layerController.onModalTouchEvent(event, popupState)
                }
            } else {
                return layerController.onModalTouchEvent(event, popupState)
            }
        }

        // 3. Brush / Color / Background Color Modals
        if (popupState == TopPopupState.BRUSH_MENU ||
            popupState == TopPopupState.COLOR_MENU ||
            popupState == TopPopupState.BG_COLOR_MENU
        ) {
            val isBgCol = (popupState == TopPopupState.BG_COLOR_MENU)
            val isColor = (popupState == TopPopupState.COLOR_MENU || isBgCol)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!brushAndColorModal.isTouchInsideModalOrBottomBar(x, y, width.toFloat(), height.toFloat())) {
                    popupState = TopPopupState.NONE
                    brushAndColorModal.subOverlay = BrushSubOverlay.NONE
                    if (layerController.isLayerDockOpen && !layerController.isLayerDockPinned && !layerController.isTouchInsideLayerDock(x, y) && y > topBarH) {
                        layerController.isLayerDockOpen = false
                    }
                    invalidate()
                    // Fall through to canvas drawing or dock/topbar interaction!
                } else {
                    return brushAndColorModal.onTouchEvent(event, width.toFloat(), height.toFloat(), isColor, isBgCol)
                }
            } else {
                return brushAndColorModal.onTouchEvent(event, width.toFloat(), height.toFloat(), isColor, isBgCol)
            }
        }

        // 4. Right-Side Layer Dock Touch Handling & Unpinned Auto-Close
        if (layerController.isLayerDockOpen && popupState == TopPopupState.NONE) {
            if (layerController.isTouchInsideLayerDock(x, y)) {
                return layerController.onDockTouchEvent(event)
            } else if (event.actionMasked == MotionEvent.ACTION_DOWN && !layerController.isLayerDockPinned) {
                // Tapping or drawing anywhere outside the unpinned Layer Dock closes it immediately!
                val topHit = findHitZone(x, y)
                if (topHit?.id != "TOP_BTN_LAYERS") {
                    layerController.isLayerDockOpen = false
                    invalidate()
                }
                // Fall through so canvas stroke or top bar tap happens in this exact touch event!
            }
        }

        // 5. Top Menu Bar Popups Outside-Tap Auto-Close
        if (event.actionMasked == MotionEvent.ACTION_DOWN && popupState != TopPopupState.NONE) {
            if (!currentPopupBounds.contains(x, y) && y > topBarH) {
                closeInlineEditor()
                popupState = TopPopupState.NONE
                invalidate()
            }
        }

        // 6. On-Canvas Eyedropper Sampling (Samples Composite Canvas)
        if (isCanvasEyedropperActive && popupState == TopPopupState.NONE && y > topBarH) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                val cPt = screenToCanvasPoint(x, y)
                val compBmp = LayerStudioCompositor.renderProjectComposite(project, forExport = false)
                val px = cPt.x.toInt().coerceIn(0, compBmp.width - 1)
                val py = cPt.y.toInt().coerceIn(0, compBmp.height - 1)
                val sampled = compBmp.getPixel(px, py)
                val finalCol = if (Color.alpha(sampled) < 20) project.backgroundColor else sampled
                if (eyedropperForBackground) {
                    brushAndColorModal.bgColorState.setFromColorInt(finalCol, recordPrevious = true)
                    brushAndColorModal.bgColorState.pushToHistory(finalCol)
                    project.backgroundColor = brushAndColorModal.bgColorState.currentColor
                    project.backgroundVisible = true
                    project.isDirty = true
                } else {
                    brushAndColorModal.colorState.setFromColorInt(finalCol, recordPrevious = true)
                    brushAndColorModal.colorState.pushToHistory(finalCol)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    isCanvasEyedropperActive = false
                }
                showBanner(String.format("Picked #%06X", 0xFFFFFF and finalCol))
                invalidate()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                isCanvasEyedropperActive = false
            }
            return true
        }

        // 7. 2-Finger Pinch Zoom, Pan & Rotation
        if (event.pointerCount >= 2 && popupState == TopPopupState.NONE) {
            abortPresetLongPress()
            pendingStrokeSamples.clear()
            isDrawingStroke = false
            strokeCommittedToBitmap = false

            val x0 = event.getX(0)
            val y0 = event.getY(0)
            val x1 = event.getX(1)
            val y1 = event.getY(1)
            val dist = hypot(x1 - x0, y1 - y0).coerceAtLeast(10f)
            val angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
            val midX = (x0 + x1) * 0.5f
            val midY = (y0 + y1) * 0.5f

            if (!isTwoFingerGesture || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                isTwoFingerGesture = true
                prevPinchDist = dist
                prevPinchAngle = angle
                prevMidX = midX
                prevMidY = midY
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = midX - prevMidX
                val dy = midY - prevMidY
                val scaleFactor = (dist / prevPinchDist).coerceIn(0.5f, 2.0f)

                var dAngle = angle - prevPinchAngle
                if (dAngle > 180f) dAngle -= 360f
                if (dAngle < -180f) dAngle += 360f

                userTransformMatrix.postTranslate(dx, dy)
                userTransformMatrix.postScale(scaleFactor, scaleFactor, midX, midY)
                userTransformMatrix.postRotate(dAngle, midX, midY)

                prevPinchDist = dist
                prevPinchAngle = angle
                prevMidX = midX
                prevMidY = midY
                invalidate()
            }
            return true
        }

        if (isTwoFingerGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isTwoFingerGesture = false
                maxPointersInGesture = 0
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                lastTouchY = y
                maxPointersInGesture = 1
                gestureDownTimeMs = System.currentTimeMillis()
                isScrollingPresets = false
                isDraggingPreset = false
                draggedPresetIndex = -1
                pendingStrokeSamples.clear()
                strokeCommittedToBitmap = false

                if (popupState == TopPopupState.NONE && y > topBarH) {
                    closeInlineEditor()
                    val activeLayer = project.activeLayer()
                    if (activeLayer.locked) {
                        showBanner("'${activeLayer.name}' is locked")
                        return true
                    }
                    if (!activeLayer.visible) {
                        showBanner("'${activeLayer.name}' is hidden")
                        return true
                    }
                    val cPt = screenToCanvasPoint(x, y)
                    val bmp = project.ensureCanvasBitmap()
                    isDrawingStroke = true
                    smoothX = cPt.x
                    smoothY = cPt.y
                    lastCanvasPt.set(cPt.x, cPt.y)
                    prevCanvasPt.set(cPt.x, cPt.y)
                    lastStrokeSpeed = 0f
                    val brushMargin = brushAndColorModal.selectedBrush().size * 0.65f + 4f
                    if (isPointNearCanvas(cPt, bmp, brushMargin)) {
                        val sxInt = cPt.x.toInt().coerceIn(0, bmp.width - 1)
                        val syInt = cPt.y.toInt().coerceIn(0, bmp.height - 1)
                        val under = bmp.getPixel(sxInt, syInt)
                        smudgeCarryColor = if (Color.alpha(under) < 15) project.backgroundColor else under
                        pendingStrokeSamples.add(QueuedStrokeSample(PointF(cPt.x, cPt.y), event.pressure))
                    }
                    return true
                }

                if (popupState == TopPopupState.NEW_SKETCH &&
                    newSketchTab == NewSketchTab.LIST_PRESETS &&
                    presetScrollAreaBounds.contains(x, y)
                ) {
                    val relY = y - presetScrollAreaBounds.top + presetListScrollY
                    val idx = (relY / presetRowHeightPx).toInt()
                    if (idx in customPresets.indices) {
                        val hitReorderHandle = findHitZone(x, y)?.id == "PRESET_REORDER"
                        val delayMs = if (hitReorderHandle) 120L else 380L
                        val r = Runnable {
                            isDraggingPreset = true
                            draggedPresetIndex = idx
                            dragCurrentY = y
                            parent?.requestDisallowInterceptTouchEvent(true)
                            invalidate()
                        }
                        longPressRunnable = r
                        postDelayed(r, delayMs)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = y - lastTouchY
                lastTouchY = y

                if (!isDrawingStroke && popupState == TopPopupState.NONE && maxPointersInGesture == 1 && y > topBarH) {
                    val activeLayer = project.activeLayer()
                    if (!activeLayer.locked && activeLayer.visible) {
                        closeInlineEditor()
                        val startPt = screenToCanvasPoint(x, y)
                        isDrawingStroke = true
                        smoothX = startPt.x
                        smoothY = startPt.y
                        lastCanvasPt.set(startPt.x, startPt.y)
                        prevCanvasPt.set(startPt.x, startPt.y)
                        lastStrokeSpeed = 0f
                    }
                }

                if (isDrawingStroke && maxPointersInGesture == 1) {
                    val rawPt = screenToCanvasPoint(x, y)
                    val bmp = project.ensureCanvasBitmap()
                    val brushMargin = brushAndColorModal.selectedBrush().size * 0.65f + 4f

                    if (!strokeCommittedToBitmap && pendingStrokeSamples.isEmpty()) {
                        if (!doesSegmentTouchCanvas(lastCanvasPt, rawPt, bmp, brushMargin)) {
                            smoothX = rawPt.x
                            smoothY = rawPt.y
                            prevCanvasPt.set(lastCanvasPt)
                            lastCanvasPt.set(rawPt)
                            return true
                        }
                        val sxInt = rawPt.x.toInt().coerceIn(0, bmp.width - 1)
                        val syInt = rawPt.y.toInt().coerceIn(0, bmp.height - 1)
                        val under = bmp.getPixel(sxInt, syInt)
                        smudgeCarryColor = if (Color.alpha(under) < 15) project.backgroundColor else under
                        smoothX = rawPt.x
                        smoothY = rawPt.y
                        pendingStrokeSamples.add(QueuedStrokeSample(PointF(lastCanvasPt.x, lastCanvasPt.y), event.pressure))
                        pendingStrokeSamples.add(QueuedStrokeSample(PointF(rawPt.x, rawPt.y), event.pressure))
                    } else {
                        smoothX += (rawPt.x - smoothX) * 0.55f
                        smoothY += (rawPt.y - smoothY) * 0.55f
                    }
                    val curPt = PointF(smoothX, smoothY)

                    val elapsed = System.currentTimeMillis() - gestureDownTimeMs
                    if (!strokeCommittedToBitmap) {
                        if (elapsed < 42L) {
                            pendingStrokeSamples.add(QueuedStrokeSample(curPt, event.pressure))
                            return true
                        }
                        commitPendingStrokeSamples()
                    }

                    stampSelectedBrushSegment(prevCanvasPt, lastCanvasPt, curPt, event.pressure)
                    project.markActiveLayerDirty()
                    prevCanvasPt.set(lastCanvasPt)
                    lastCanvasPt.set(curPt)
                    invalidate()
                    return true
                }

                if (isDraggingPreset && draggedPresetIndex in customPresets.indices) {
                    dragCurrentY = y
                    val relY = (y - presetScrollAreaBounds.top + presetListScrollY).coerceAtLeast(0f)
                    val targetIdx = (relY / presetRowHeightPx).toInt().coerceIn(0, customPresets.size - 1)
                    if (targetIdx != draggedPresetIndex) {
                        val moved = customPresets.removeAt(draggedPresetIndex)
                        customPresets.add(targetIdx, moved)
                        draggedPresetIndex = targetIdx
                        ProjectStorageManager.saveCustomPresets(context, customPresets)
                    }
                    invalidate()
                    return true
                }

                if (abs(y - touchDownY) > 16f * sx || abs(x - touchDownX) > 16f * sx) {
                    abortPresetLongPress()
                    if (popupState == TopPopupState.NEW_SKETCH &&
                        newSketchTab == NewSketchTab.LIST_PRESETS &&
                        presetScrollAreaBounds.contains(touchDownX, touchDownY)
                    ) {
                        isScrollingPresets = true
                        presetListScrollY -= dy
                        invalidate()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                abortPresetLongPress()
                if (isDrawingStroke) {
                    if (maxPointersInGesture == 1 && !strokeCommittedToBitmap && pendingStrokeSamples.isNotEmpty()) {
                        commitPendingStrokeSamples()
                    }
                    project.markActiveLayerDirty()
                    pendingStrokeSamples.clear()
                    isDrawingStroke = false
                    strokeCommittedToBitmap = false
                    maxPointersInGesture = 0
                    invalidate()
                    return true
                }
                if (isDraggingPreset) {
                    isDraggingPreset = false
                    draggedPresetIndex = -1
                    maxPointersInGesture = 0
                    invalidate()
                    return true
                }
                if (!isScrollingPresets &&
                    abs(x - touchDownX) < 28f * sx && abs(y - touchDownY) < 28f * sx
                ) {
                    handleTap(x, y)
                }
                isScrollingPresets = false
                maxPointersInGesture = 0
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                abortPresetLongPress()
                pendingStrokeSamples.clear()
                isDrawingStroke = false
                strokeCommittedToBitmap = false
                isDraggingPreset = false
                isScrollingPresets = false
                maxPointersInGesture = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun commitPendingStrokeSamples() {
        if (strokeCommittedToBitmap || pendingStrokeSamples.isEmpty()) return
        strokeCommittedToBitmap = true
        project.pushUndoSnapshot()
        project.markActiveLayerDirty()
        if (!brushAndColorModal.colorState.isColorlessEraser) {
            brushAndColorModal.colorState.pushToHistory(brushAndColorModal.colorState.currentColor)
        }

        procHistoryPts.clear()
        strokeCarryDist = 0f
        val first = pendingStrokeSamples[0].pt
        prevCanvasPt.set(first)
        lastCanvasPt.set(first)
        stampSelectedBrushSegment(prevCanvasPt, lastCanvasPt, first, pendingStrokeSamples[0].pressure)

        for (i in 1 until pendingStrokeSamples.size) {
            val s = pendingStrokeSamples[i]
            stampSelectedBrushSegment(prevCanvasPt, lastCanvasPt, s.pt, s.pressure)
            prevCanvasPt.set(lastCanvasPt)
            lastCanvasPt.set(s.pt)
        }
        pendingStrokeSamples.clear()
    }

    private fun isPointNearCanvas(pt: PointF, bmp: android.graphics.Bitmap, margin: Float): Boolean {
        return pt.x in -margin..(bmp.width.toFloat() + margin) &&
               pt.y in -margin..(bmp.height.toFloat() + margin)
    }

    private fun doesSegmentTouchCanvas(p0: PointF, p1: PointF, bmp: android.graphics.Bitmap, margin: Float): Boolean {
        val left = -margin
        val top = -margin
        val right = bmp.width.toFloat() + margin
        val bottom = bmp.height.toFloat() + margin
        if (max(p0.x, p1.x) < left || min(p0.x, p1.x) > right) return false
        if (max(p0.y, p1.y) < top || min(p0.y, p1.y) > bottom) return false
        return true
    }

    private fun screenToCanvasPoint(sx: Float, sy: Float): PointF {
        val pts = floatArrayOf(sx, sy)
        inverseViewMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    private var strokeCarryDist: Float = 0f
    private var dabCounter: Int = 0

    private fun stampSelectedBrushSegment(p0: PointF, p1: PointF, p2: PointF, rawPressure: Float) {
        val activeLayer = project.activeLayer()
        if (activeLayer.locked || !activeLayer.visible) return
        val bmp = project.ensureCanvasBitmap()
        val c = Canvas(bmp)
        val brush = brushAndColorModal.selectedBrush()
        val colState = brushAndColorModal.colorState

        val mid1X = (p0.x + p1.x) * 0.5f
        val mid1Y = (p0.y + p1.y) * 0.5f
        val mid2X = (p1.x + p2.x) * 0.5f
        val mid2Y = (p1.y + p2.y) * 0.5f

        val segDist = hypot(mid2X - mid1X, mid2Y - mid1Y)
        lastStrokeSpeed = lastStrokeSpeed * 0.7f + segDist * 0.3f

        val pNorm = if (rawPressure > 0.01f && rawPressure != 1.0f) {
            rawPressure.coerceIn(0.08f, 1.0f)
        } else {
            (1f - (lastStrokeSpeed / 85f).coerceIn(0f, 0.55f))
        }

        val sizePressScale = (brush.sizeLightPressure + (brush.sizeHeavyPressure - brush.sizeLightPressure) * pNorm) / 100f
        val curRadius = (brush.size * 0.5f * sizePressScale.coerceIn(0.10f, 1.5f)).coerceAtLeast(1.0f)
        val stepSpacingPx = max(1.0f, curRadius * 2f * (brush.spacing / 100f).coerceIn(0.03f, 2.5f))
        val tangentDeg = Math.toDegrees(atan2((mid2Y - mid1Y).toDouble(), (mid2X - mid1X).toDouble())).toFloat()
        val smudgeHolder = intArrayOf(smudgeCarryColor)

        if (segDist < 0.001f && strokeCarryDist == 0f) {
            UnifiedBrushRenderer.renderSingleDab(
                canvas = c,
                brush = brush,
                x = mid1X,
                y = mid1Y,
                tangentDeg = tangentDeg,
                pressureNorm = pNorm,
                baseColor = colState.currentColor,
                isTransparentColor = colState.isColorlessEraser,
                dabIndex = dabCounter++,
                deterministicRandom = false,
                procHistory = procHistoryPts,
                smudgeBitmap = bmp,
                smudgeCarryColorHolder = smudgeHolder,
                alphaLock = activeLayer.alphaLock
            )
            smudgeCarryColor = smudgeHolder[0]
            strokeCarryDist = 0.001f
            return
        }

        var distLeft = strokeCarryDist + segDist
        if (distLeft >= stepSpacingPx && segDist > 0.0001f) {
            var traveled = stepSpacingPx - strokeCarryDist
            while (traveled <= segDist) {
                val t = (traveled / segDist).coerceIn(0f, 1f)
                val invT = 1f - t
                val x = invT * invT * mid1X + 2f * invT * t * p1.x + t * t * mid2X
                val y = invT * invT * mid1Y + 2f * invT * t * p1.y + t * t * mid2Y

                UnifiedBrushRenderer.renderSingleDab(
                    canvas = c,
                    brush = brush,
                    x = x,
                    y = y,
                    tangentDeg = tangentDeg,
                    pressureNorm = pNorm,
                    baseColor = colState.currentColor,
                    isTransparentColor = colState.isColorlessEraser,
                    dabIndex = dabCounter++,
                    deterministicRandom = false,
                    procHistory = procHistoryPts,
                    smudgeBitmap = bmp,
                    smudgeCarryColorHolder = smudgeHolder,
                    alphaLock = activeLayer.alphaLock
                )
                smudgeCarryColor = smudgeHolder[0]
                traveled += stepSpacingPx
            }
            strokeCarryDist = segDist - (traveled - stepSpacingPx)
        } else {
            strokeCarryDist = distLeft
        }
    }

    private fun abortPresetLongPress() {
        val r = longPressRunnable
        if (r != null) {
            removeCallbacks(r)
            longPressRunnable = null
        }
    }

    private fun findHitZone(x: Float, y: Float): HitZone? {
        for (i in hitZones.size - 1 downTo 0) {
            if (hitZones[i].rect.contains(x, y)) return hitZones[i]
        }
        return null
    }

    private fun handleTap(x: Float, y: Float) {
        val zone = findHitZone(x, y)
        val sx = width / 1080f
        val pal = uiPalette

        if (zone == null) {
            closeInlineEditor()
            if (popupState != TopPopupState.NONE && !currentPopupBounds.contains(x, y)) {
                popupState = TopPopupState.NONE
                invalidate()
            }
            return
        }

        if (!zone.id.startsWith("INPUT_") && !zone.id.startsWith("EDIT_")) {
            closeInlineEditor()
        }

        when (zone.id) {
            "TOP_BTN_1" -> {
                popupState = if (popupState == TopPopupState.BUTTON1_MAIN) TopPopupState.NONE else TopPopupState.BUTTON1_MAIN
                invalidate()
            }
            "TOP_BTN_UNDO" -> {
                if (project.undo()) invalidate()
            }
            "TOP_BTN_REDO" -> {
                if (project.redo()) invalidate()
            }
            "TOP_BTN_BRUSH" -> {
                popupState = if (popupState == TopPopupState.BRUSH_MENU) TopPopupState.NONE else TopPopupState.BRUSH_MENU
                invalidate()
            }
            "TOP_BTN_COLOR" -> {
                popupState = if (popupState == TopPopupState.COLOR_MENU) TopPopupState.NONE else TopPopupState.COLOR_MENU
                invalidate()
            }
            "TOP_BTN_LAYERS" -> {
                popupState = TopPopupState.NONE
                layerController.isLayerDockOpen = !layerController.isLayerDockOpen
                invalidate()
            }
            "TOP_BTN_FIT_VIEW" -> {
                resetCanvasTransform()
                showBanner("Fit to View")
            }
            "BACK_TO_MAIN" -> {
                popupState = TopPopupState.BUTTON1_MAIN
                invalidate()
            }
            "BACK_TO_PRESET_LIST" -> {
                popupState = TopPopupState.NEW_SKETCH
                newSketchTab = NewSketchTab.LIST_PRESETS
                invalidate()
            }
            "BACK_TO_SHARE_MAIN" -> {
                popupState = TopPopupState.SHARE_EXPORT_MAIN
                invalidate()
            }
            "EDIT_PROJECT_NAME" -> {
                activeInlineField = InlineEditField.PROJECT_NAME_BOX
                callbacks.onBeginInlineEdit(
                    field = InlineEditField.PROJECT_NAME_BOX,
                    bounds = zone.rect,
                    initialText = project.name,
                    numeric = false,
                    centered = false,
                    textSizePx = 34f * sx,
                    textColor = pal.textPrimary,
                    bgColor = pal.inputBg
                )
                invalidate()
            }
            "INPUT_WIDTH_BOX" -> {
                activeInlineField = InlineEditField.WIDTH_BOX
                callbacks.onBeginInlineEdit(
                    field = InlineEditField.WIDTH_BOX,
                    bounds = zone.rect,
                    initialText = customWidthInput.toString(),
                    numeric = true,
                    centered = true,
                    textSizePx = 37f * sx,
                    textColor = pal.textPrimary,
                    bgColor = pal.inputBg
                )
                invalidate()
            }
            "INPUT_HEIGHT_BOX" -> {
                activeInlineField = InlineEditField.HEIGHT_BOX
                callbacks.onBeginInlineEdit(
                    field = InlineEditField.HEIGHT_BOX,
                    bounds = zone.rect,
                    initialText = customHeightInput.toString(),
                    numeric = true,
                    centered = true,
                    textSizePx = 37f * sx,
                    textColor = pal.textPrimary,
                    bgColor = pal.inputBg
                )
                invalidate()
            }
            "EDIT_PRESET_NAME_FIELD" -> {
                activeInlineField = InlineEditField.PRESET_NAME_BOX
                callbacks.onBeginInlineEdit(
                    field = InlineEditField.PRESET_NAME_BOX,
                    bounds = zone.rect,
                    initialText = editingPresetName,
                    numeric = false,
                    centered = true,
                    textSizePx = 37f * sx,
                    textColor = pal.textPrimary,
                    bgColor = pal.inputBg
                )
                invalidate()
            }
            "MENU_NEW_SKETCH" -> {
                popupState = TopPopupState.NEW_SKETCH
                val last = ProjectStorageManager.loadLastDimensions(context, hwScreenSize.first, hwScreenSize.second)
                customWidthInput = last.first
                customHeightInput = last.second
                lockedAspectRatio = customWidthInput.toDouble() / max(1, customHeightInput).toDouble()
                invalidate()
            }
            "MENU_SAVE" -> {
                popupState = TopPopupState.SAVE_MENU
                invalidate()
            }
            "MENU_IMPORT" -> {
                popupState = TopPopupState.NONE
                invalidate()
                checkSaveOrDiscardIfDirty {
                    callbacks.onRequestImportImage()
                }
            }
            "MENU_PREFS" -> {
                popupState = TopPopupState.PREFERENCES_SCREEN
                invalidate()
            }
            "MENU_SHARE" -> {
                popupState = TopPopupState.SHARE_EXPORT_MAIN
                invalidate()
            }
            "TAB_PLUS" -> {
                newSketchTab = NewSketchTab.PLUS_CUSTOM
                invalidate()
            }
            "TAB_LIST" -> {
                newSketchTab = NewSketchTab.LIST_PRESETS
                invalidate()
            }
            "ROTATE_CANVAS_PREVIEW" -> {
                val tmp = customWidthInput
                customWidthInput = customHeightInput
                customHeightInput = tmp
                if (customWidthInput > 0 && customHeightInput > 0) {
                    lockedAspectRatio = customWidthInput.toDouble() / customHeightInput.toDouble()
                }
                invalidate()
            }
            "TOGGLE_RATIO_LOCK" -> {
                isRatioLocked = !isRatioLocked
                if (isRatioLocked && customWidthInput > 0 && customHeightInput > 0) {
                    lockedAspectRatio = customWidthInput.toDouble() / customHeightInput.toDouble()
                }
                invalidate()
            }
            "BTN_CREATE_CUSTOM" -> {
                if (customWidthInput > 0 && customHeightInput > 0) {
                    val targetW = customWidthInput
                    val targetH = customHeightInput
                    ProjectStorageManager.saveLastDimensions(context, targetW, targetH)
                    requestCreateNewProject(targetW, targetH)
                }
            }
            "PRESET_SCREEN" -> {
                requestCreateNewProject(hwScreenSize.first, hwScreenSize.second)
            }
            "PRESET_LAST" -> {
                val last = ProjectStorageManager.loadLastDimensions(context, hwScreenSize.first, hwScreenSize.second)
                requestCreateNewProject(last.first, last.second)
            }
            "PRESET_SELECT" -> {
                val idx = zone.payload
                if (idx in customPresets.indices) {
                    val p = customPresets[idx]
                    ProjectStorageManager.saveLastDimensions(context, p.width, p.height)
                    requestCreateNewProject(p.width, p.height)
                }
            }
            "PRESET_GEAR" -> {
                val idx = zone.payload
                if (idx in customPresets.indices) {
                    val p = customPresets[idx]
                    editingPresetIndex = idx
                    editingPresetName = p.name
                    customWidthInput = p.width
                    customHeightInput = p.height
                    isRatioLocked = false
                    lockedAspectRatio = p.width.toDouble() / max(1, p.height).toDouble()
                    popupState = TopPopupState.EDIT_PRESET
                    invalidate()
                }
            }
            "BTN_ADD_PRESET" -> {
                editingPresetIndex = -1
                editingPresetName = "Custom ${customPresets.size + 1}"
                customWidthInput = hwScreenSize.first
                customHeightInput = hwScreenSize.second
                isRatioLocked = false
                lockedAspectRatio = customWidthInput.toDouble() / max(1, customHeightInput).toDouble()
                popupState = TopPopupState.EDIT_PRESET
                invalidate()
            }
            "BTN_DELETE_PRESET" -> {
                if (editingPresetIndex in customPresets.indices) {
                    customPresets.removeAt(editingPresetIndex)
                    ProjectStorageManager.saveCustomPresets(context, customPresets)
                    popupState = TopPopupState.NEW_SKETCH
                    newSketchTab = NewSketchTab.LIST_PRESETS
                    invalidate()
                }
            }
            "BTN_DONE_EDIT_PRESET" -> {
                if (customWidthInput > 0 && customHeightInput > 0) {
                    if (editingPresetIndex in customPresets.indices) {
                        val p = customPresets[editingPresetIndex]
                        p.name = editingPresetName.ifBlank { "Preset" }
                        p.width = customWidthInput
                        p.height = customHeightInput
                    } else {
                        customPresets.add(
                            CanvasPreset(
                                id = "p_${System.currentTimeMillis()}",
                                name = editingPresetName.ifBlank { "Preset" },
                                width = customWidthInput,
                                height = customHeightInput
                            )
                        )
                    }
                    ProjectStorageManager.saveCustomPresets(context, customPresets)
                    popupState = TopPopupState.NEW_SKETCH
                    newSketchTab = NewSketchTab.LIST_PRESETS
                    invalidate()
                }
            }
            "ACTION_SAVE_CURRENT" -> performAsyncSave(asCopy = false, onAfterSave = null)
            "ACTION_SAVE_COPY" -> performAsyncSave(asCopy = true, onAfterSave = null)
            "EXPORT_FMT_PNG", "EXPORT_FMT_PSD", "EXPORT_FMT_PDF" -> performAsyncStaticFormatExport("PNG")
            "EXPORT_FMT_JPG" -> performAsyncStaticFormatExport("JPG")
            "EXPORT_FMT_TIF" -> performAsyncStaticFormatExport("TIF")
            "OPEN_EXPORT_AS" -> {
                popupState = TopPopupState.EXPORT_AS_MENU
                invalidate()
            }
            "OPEN_SHARE_AS" -> {
                popupState = TopPopupState.SHARE_AS_MENU
                invalidate()
            }
        }
    }

    private fun requestCreateNewProject(targetW: Int, targetH: Int) {
        popupState = TopPopupState.NONE
        invalidate()
        checkSaveOrDiscardIfDirty {
            project.releaseAll()
            project = SketchProjectState(
                name = SketchProjectState.nextDefaultName(),
                width = targetW,
                height = targetH,
                isDirty = false,
                isSavedOnce = false
            )
            project.ensureLayersInitialized()
            resetCanvasTransform()
            showBanner("New Sketch (${project.name}): ${targetW} × ${targetH}")
            invalidate()
        }
    }

    fun applyLoadedMultiLayerProject(loaded: SketchProjectState) {
        project.releaseAll()
        project = loaded
        project.ensureLayersInitialized()
        brushAndColorModal.syncBgColorFromProject(project.backgroundColor)
        resetCanvasTransform()
        ProjectStorageManager.saveLastDimensions(context, project.width, project.height)
        showBanner("Opened '${project.name}' (${project.layers.size} layer${if (project.layers.size == 1) "" else "s"})")
        invalidate()
    }

    fun applyImportedBitmapAsNewProject(bmp: android.graphics.Bitmap, suggestedName: String) {
        project.releaseAll()
        project = SketchProjectState(
            name = suggestedName.ifBlank { SketchProjectState.nextDefaultName() },
            width = bmp.width,
            height = bmp.height,
            isDirty = true,
            isSavedOnce = false
        )
        project.ensureLayersInitialized()
        val layerBmp = project.ensureCanvasBitmap()
        val c = Canvas(layerBmp)
        c.drawBitmap(bmp, null, RectF(0f, 0f, layerBmp.width.toFloat(), layerBmp.height.toFloat()), null)
        bmp.recycle()
        project.markActiveLayerDirty()
        resetCanvasTransform()
        ProjectStorageManager.saveLastDimensions(context, project.width, project.height)
        showBanner("Imported Project: ${project.width} × ${project.height}")
        invalidate()
    }

    private fun checkSaveOrDiscardIfDirty(onProceed: () -> Unit) {
        if (!project.isDirty) {
            onProceed()
            return
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 48, 56, 36)
            setBackgroundColor(Color.rgb(243, 243, 243))
        }

        val titleTv = TextView(context).apply {
            text = "Save changes to current sketch?"
            textSize = 18f
            setTextColor(Color.rgb(25, 25, 25))
            setPadding(0, 0, 0, 36)
        }
        container.addView(titleTv)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val dialog = AlertDialog.Builder(context).setView(container).create()

        fun makeSharpBtn(label: String, bgColor: Int, txtColor: Int, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(txtColor)
                setBackgroundColor(bgColor)
                setPadding(42, 24, 42, 24)
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
        }

        val cancelBtn = makeSharpBtn("CANCEL", Color.rgb(220, 220, 220), Color.rgb(40, 40, 40)) {}
        val discardBtn = makeSharpBtn("DISCARD", Color.rgb(215, 75, 75), Color.WHITE) { onProceed() }
        val saveBtn = makeSharpBtn("SAVE", Color.rgb(55, 116, 205), Color.WHITE) {
            performAsyncSave(asCopy = false, onAfterSave = onProceed)
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 20 }

        btnRow.addView(cancelBtn)
        btnRow.addView(discardBtn, lp)
        btnRow.addView(saveBtn, lp)
        container.addView(btnRow)
        dialog.show()
    }

    private fun performAsyncSave(asCopy: Boolean, onAfterSave: (() -> Unit)?) {
        if (isSavingInProgress) return
        closeInlineEditor()
        savingModalTitle = "Saving sketch..."
        savingModalSubtitle = "${project.width} × ${project.height} px (${project.layers.size}L PNG + TIFF)"
        savingProgressFraction = -1f
        isSavingInProgress = true
        popupState = TopPopupState.NONE
        invalidate()

        val appCtx = context.applicationContext

        Thread {
            val res = ProjectStorageManager.saveProjectToDevice(
                context = appCtx,
                project = project,
                asCopy = asCopy
            )
            post {
                isSavingInProgress = false
                savingProgressFraction = -1f
                res.onSuccess { path ->
                    project.isSavedOnce = true
                    project.isDirty = false
                    project.lastSavedPath = path
                    showBanner(if (asCopy) "Saved Copy -> $path" else "Saved -> $path")
                    onAfterSave?.invoke()
                }.onFailure { err ->
                    showBanner("Save failed: ${err.message}")
                }
                invalidate()
            }
        }.start()
    }

    private fun performAsyncStaticFormatExport(formatCode: String) {
        if (isSavingInProgress) return
        closeInlineEditor()
        savingModalTitle = "Exporting $formatCode..."
        savingModalSubtitle = "${project.width} × ${project.height} px"
        savingProgressFraction = -1f
        isSavingInProgress = true
        popupState = TopPopupState.NONE
        invalidate()

        val appCtx = context.applicationContext

        Thread {
            val res = ProjectStorageManager.exportStaticFormatToDevice(
                context = appCtx,
                project = project,
                formatCode = formatCode
            )
            post {
                isSavingInProgress = false
                savingProgressFraction = -1f
                res.onSuccess { path ->
                    project.isSavedOnce = true
                    project.isDirty = false
                    project.lastSavedPath = path
                    showBanner("Exported $formatCode -> $path")
                }.onFailure { err ->
                    showBanner("Export failed: ${err.message}")
                }
                invalidate()
            }
        }.start()
    }

    fun showSimpleInputModal(title: String, initialValue: String, onConfirm: (String) -> Unit) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 42, 52, 36)
            setBackgroundColor(Color.rgb(243, 243, 243))
        }
        val header = TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(0, 0, 0, 20)
        }
        root.addView(header)

        val input = EditText(context).apply {
            setText(initialValue)
            setSelection(text.length)
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            textSize = 17f
            setPadding(28, 22, 28, 22)
        }
        root.addView(input)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 32, 0, 0)
        }
        val dlg = AlertDialog.Builder(context).setView(root).create()

        val cancelBtn = TextView(context).apply {
            text = "CANCEL"
            setTextColor(Color.rgb(50, 50, 50))
            setBackgroundColor(Color.rgb(220, 220, 220))
            setPadding(40, 22, 40, 22)
            setOnClickListener { dlg.dismiss() }
        }
        val okBtn = TextView(context).apply {
            text = "OK"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(55, 116, 205))
            setPadding(52, 22, 52, 22)
            setOnClickListener {
                dlg.dismiss()
                onConfirm(input.text.toString())
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 20 }
        btnRow.addView(cancelBtn)
        btnRow.addView(okBtn, lp)
        root.addView(btnRow)
        dlg.show()
    }

    fun showConfirmDeleteSetModal(setName: String, onConfirmed: () -> Unit) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 42, 52, 36)
            setBackgroundColor(Color.rgb(243, 243, 243))
        }
        val header = TextView(context).apply {
            text = "Delete Set '$setName'?"
            textSize = 18f
            setTextColor(Color.rgb(185, 45, 45))
            setPadding(0, 0, 0, 14)
        }
        root.addView(header)

        val sub = TextView(context).apply {
            text = "Type 'confirm' below to permanently delete this brush set:"
            textSize = 14f
            setTextColor(Color.rgb(75, 75, 75))
            setPadding(0, 0, 0, 20)
        }
        root.addView(sub)

        val input = EditText(context).apply {
            hint = "confirm"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            textSize = 17f
            setPadding(28, 22, 28, 22)
        }
        root.addView(input)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 32, 0, 0)
        }
        val dlg = AlertDialog.Builder(context).setView(root).create()

        val cancelBtn = TextView(context).apply {
            text = "CANCEL"
            setTextColor(Color.rgb(50, 50, 50))
            setBackgroundColor(Color.rgb(220, 220, 220))
            setPadding(40, 22, 40, 22)
            setOnClickListener { dlg.dismiss() }
        }
        val delBtn = TextView(context).apply {
            text = "DELETE SET"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(195, 50, 50))
            setPadding(44, 22, 44, 22)
            setOnClickListener {
                if (input.text.toString().trim().equals("confirm", ignoreCase = true)) {
                    dlg.dismiss()
                    onConfirmed()
                } else {
                    showBanner("Please type 'confirm' to delete set")
                }
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 20 }
        btnRow.addView(cancelBtn)
        btnRow.addView(delBtn, lp)
        root.addView(btnRow)
        dlg.show()
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
