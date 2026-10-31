package com.sketchbook.oss

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object ProjectStorageManager {

    private const val PREFS_NAME = "sketchbook_classic_prefs"
    private const val TAG_SBLY_CHUNK = 37724

    fun loadCustomPresets(context: Context): MutableList<CanvasPreset> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_presets_v1", null)
        val list = mutableListOf<CanvasPreset>()
        if (raw.isNullOrEmpty()) {
            list.addAll(
                listOf(
                    CanvasPreset("p_letter", "Letter", 2550, 3300),
                    CanvasPreset("p_a4", "A4", 2480, 3508),
                    CanvasPreset("p_a5", "A5", 1748, 2480),
                    CanvasPreset("p_4x6", "4 x 6", 1200, 1800),
                    CanvasPreset("p_5x7", "5 x 7", 1500, 2100)
                )
            )
            saveCustomPresets(context, list)
            return list
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    CanvasPreset(
                        id = o.optString("id", "p_$i"),
                        name = o.optString("name", "Preset"),
                        width = o.optInt("width", 1080),
                        height = o.optInt("height", 2408)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveCustomPresets(context: Context, presets: List<CanvasPreset>) {
        val arr = JSONArray()
        for (p in presets) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("width", p.width)
            o.put("height", p.height)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("custom_presets_v1", arr.toString())
            .apply()
    }

    fun loadLastDimensions(context: Context, screenW: Int, screenH: Int): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val w = prefs.getInt("last_w", screenW)
        val h = prefs.getInt("last_h", screenH)
        return Pair(w, h)
    }

    fun saveLastDimensions(context: Context, w: Int, h: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("last_w", w)
            .putInt("last_h", h)
            .apply()
    }

    /**
     * Saves both the full-resolution .png (preserving transparent background if backgroundVisible == false)
     * AND the Multi-Layer SketchBook .tif (containing all layers, blend modes, opacities, locks & background config).
     */
    fun saveProjectToDevice(
        context: Context,
        project: SketchProjectState,
        asCopy: Boolean,
        paperColor: Int = Color.WHITE
    ): Result<String> {
        return try {
            project.ensureLayersInitialized()
            val safeBase = sanitizeFileName(project.name.ifBlank { "Untitled-1" })
            val finalName = if (asCopy) "${safeBase}_copy_${System.currentTimeMillis() % 10000}" else safeBase
            val exactW = project.width.coerceAtLeast(1)
            val exactH = project.height.coerceAtLeast(1)

            val compBmp = LayerStudioCompositor.renderProjectComposite(project, forExport = true)
            val outBmp = compBmp.copy(Bitmap.Config.ARGB_8888, false)

            val savedPngLocation = saveImageToMediaStoreOrDisk(
                context, outBmp, finalName, "png", "image/png", Bitmap.CompressFormat.PNG, 100
            )
            val tiffDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects")
            if (!tiffDir.exists()) tiffDir.mkdirs()
            val tiffFile = File(tiffDir, "$finalName.tif")
            FileOutputStream(tiffFile).use { fos ->
                writeSketchbookMultiLayerTiff(outBmp, project, fos, exactW, exactH)
            }
            val savedTiffLocation = saveTiffToPublicStorage(context, tiffFile, "$finalName.tif")
            outBmp.recycle()
            Result.success("$savedPngLocation + $savedTiffLocation")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun exportStaticFormatToDevice(
        context: Context,
        project: SketchProjectState,
        formatCode: String,
        paperColor: Int = Color.WHITE
    ): Result<String> {
        return try {
            project.ensureLayersInitialized()
            val safeBase = sanitizeFileName(project.name.ifBlank { "Untitled-1" })
            val exactW = project.width.coerceAtLeast(1)
            val exactH = project.height.coerceAtLeast(1)

            val compBmp = LayerStudioCompositor.renderProjectComposite(project, forExport = true)
            val outBmp = compBmp.copy(Bitmap.Config.ARGB_8888, false)

            val loc = when (formatCode.uppercase()) {
                "JPG", "JPEG" -> {
                    // JPEG does not support alpha; composite over white if background was hidden
                    val jpgBmp = if (!project.backgroundVisible) {
                        val b = Bitmap.createBitmap(exactW, exactH, Bitmap.Config.ARGB_8888)
                        val c = Canvas(b)
                        c.drawColor(Color.WHITE)
                        c.drawBitmap(outBmp, 0f, 0f, null)
                        b
                    } else outBmp
                    val saved = saveImageToMediaStoreOrDisk(
                        context, jpgBmp, safeBase, "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 96
                    )
                    if (jpgBmp !== outBmp) jpgBmp.recycle()
                    saved
                }
                "TIF", "TIFF" -> {
                    val tiffBytes = File(context.cacheDir, "export_${System.currentTimeMillis()}.tif")
                    FileOutputStream(tiffBytes).use { fos ->
                        writeSketchbookMultiLayerTiff(outBmp, project, fos, exactW, exactH)
                    }
                    val resLoc = saveTiffToPublicStorage(context, tiffBytes, "$safeBase.tif")
                    tiffBytes.delete()
                    resLoc
                }
                else -> saveImageToMediaStoreOrDisk(
                    context, outBmp, safeBase, "png", "image/png", Bitmap.CompressFormat.PNG, 100
                )
            }
            outBmp.recycle()
            Result.success("$loc (${exactW}×${exactH})")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds the lossless SBLY multi-layer payload containing project metadata,
     * background configuration, custom color label names, and PNG-compressed ARGB_8888 layer bitmaps.
     */
    private fun buildMultiLayerChunkBytes(project: SketchProjectState): ByteArray {
        val layerPngList = ArrayList<ByteArray>(project.layers.size)
        val layersJson = JSONArray()

        for (l in project.layers) {
            val baos = ByteArrayOutputStream()
            l.bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val pngBytes = baos.toByteArray()
            layerPngList.add(pngBytes)

            val lo = JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("visible", l.visible)
                put("alphaLock", l.alphaLock)
                put("locked", l.locked)
                put("clippingMask", l.clippingMask)
                put("opacity", l.opacity.toDouble())
                put("blendMode", l.blendMode.name)
                put("colorLabelIndex", l.colorLabelIndex)
                put("temporalStrokesJson", l.temporalStrokesJson)
                put("pngByteLen", pngBytes.size)
            }
            layersJson.put(lo)
        }

        val rootJson = JSONObject().apply {
            put("magic", "SKETCHBOOK_OSS_LAYERS_V2")
            put("name", project.name)
            put("width", project.width)
            put("height", project.height)
            put("selectedLayerIndex", project.selectedLayerIndex)
            put("backgroundColor", project.backgroundColor)
            put("backgroundVisible", project.backgroundVisible)
            put("backgroundConfig", project.backgroundConfig.toJson())
            put("colorLabelNames", ColorLabelPalette.toJsonArray())
            put("layers", layersJson)
        }

        val jsonBytes = rootJson.toString().toByteArray(Charsets.UTF_8)
        var totalPngLen = 0
        for (b in layerPngList) totalPngLen += b.size

        // Binary layout: "SBLY" (4B) + version=2 (4B) + jsonLen (4B) + jsonBytes + concatenated PNG byte arrays
        val outBuf = ByteBuffer.allocate(12 + jsonBytes.size + totalPngLen).order(ByteOrder.LITTLE_ENDIAN)
        outBuf.put('S'.code.toByte())
        outBuf.put('B'.code.toByte())
        outBuf.put('L'.code.toByte())
        outBuf.put('Y'.code.toByte())
        outBuf.putInt(2)
        outBuf.putInt(jsonBytes.size)
        outBuf.put(jsonBytes)
        for (b in layerPngList) {
            outBuf.put(b)
        }
        return outBuf.array()
    }

    /**
     * Attempts to parse a multi-layer SketchBook .tif (or standard baseline .tif) from [rawBytes].
     * Returns a fully reconstructed SketchProjectState if it contains our SBLY chunk or valid TIFF IFD.
     */
    fun tryLoadProjectFromTiffBytes(context: Context, rawBytes: ByteArray, suggestedName: String): SketchProjectState? {
        try {
            // 1. Search for "SBLY" magic header inside the file
            var sblyPos = -1
            val limit = rawBytes.size - 12
            for (i in 0..limit) {
                if (rawBytes[i] == 'S'.code.toByte() &&
                    rawBytes[i + 1] == 'B'.code.toByte() &&
                    rawBytes[i + 2] == 'L'.code.toByte() &&
                    rawBytes[i + 3] == 'Y'.code.toByte()
                ) {
                    val ver = ByteBuffer.wrap(rawBytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val jLen = ByteBuffer.wrap(rawBytes, i + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (ver == 2 && jLen in 10..(rawBytes.size - i - 12)) {
                        sblyPos = i
                        break
                    }
                }
            }

            if (sblyPos >= 0) {
                val buf = ByteBuffer.wrap(rawBytes, sblyPos + 8, rawBytes.size - (sblyPos + 8)).order(ByteOrder.LITTLE_ENDIAN)
                val jsonLen = buf.int
                val jsonStr = String(rawBytes, sblyPos + 12, jsonLen, Charsets.UTF_8)
                val root = JSONObject(jsonStr)
                val w = root.optInt("width", 1080).coerceIn(1, 8192)
                val h = root.optInt("height", 2408).coerceIn(1, 8192)
                val projName = root.optString("name", suggestedName).ifBlank { suggestedName }

                val proj = SketchProjectState(
                    name = projName,
                    width = w,
                    height = h,
                    isDirty = false,
                    isSavedOnce = true
                )
                proj.backgroundColor = root.optInt("backgroundColor", Color.WHITE)
                proj.backgroundVisible = root.optBoolean("backgroundVisible", true)
                proj.backgroundConfig.fromJson(root.optJSONObject("backgroundConfig"))
                ColorLabelPalette.fromJsonArray(root.optJSONArray("colorLabelNames"))
                ColorLabelPalette.saveToPrefs(context)

                val layersArr = root.getJSONArray("layers")
                var binOffset = sblyPos + 12 + jsonLen
                proj.layers.clear()

                for (i in 0 until layersArr.length()) {
                    val lo = layersArr.getJSONObject(i)
                    val pngLen = lo.getInt("pngByteLen")
                    val decodedImmut = BitmapFactory.decodeByteArray(rawBytes, binOffset, pngLen)
                    binOffset += pngLen

                    val layerBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    if (decodedImmut != null) {
                        val c = Canvas(layerBmp)
                        c.drawBitmap(decodedImmut, 0f, 0f, null)
                        decodedImmut.recycle()
                    }
                    proj.layers.add(
                        SketchLayer(
                            id = lo.optString("id", "layer_$i"),
                            name = lo.optString("name", "Layer ${i + 1}"),
                            bitmap = layerBmp,
                            visible = lo.optBoolean("visible", true),
                            alphaLock = lo.optBoolean("alphaLock", false),
                            locked = lo.optBoolean("locked", false),
                            clippingMask = lo.optBoolean("clippingMask", false),
                            opacity = lo.optDouble("opacity", 100.0).toFloat().coerceIn(0f, 100f),
                            blendMode = LayerBlendMode.fromNameOrDefault(lo.optString("blendMode", "NORMAL")),
                            colorLabelIndex = lo.optInt("colorLabelIndex", 0).coerceIn(0, 20),
                            temporalStrokesJson = lo.optString("temporalStrokesJson", "")
                        )
                    )
                }
                proj.ensureLayersInitialized()
                proj.selectedLayerIndex = root.optInt("selectedLayerIndex", 0).coerceIn(0, proj.layers.lastIndex)
                return proj
            }

            // 2. Fallback: If it's a baseline uncompressed RGBA Little-Endian TIFF without SBLY chunk
            if (rawBytes.size > 24 &&
                rawBytes[0] == 'I'.code.toByte() &&
                rawBytes[1] == 'I'.code.toByte() &&
                rawBytes[2] == 42.toByte()
            ) {
                val bb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
                val ifdOffset = bb.getInt(4)
                if (ifdOffset in 8..(rawBytes.size - 14)) {
                    val entries = bb.getShort(ifdOffset).toInt() and 0xFFFF
                    var w = 0
                    var h = 0
                    var stripOffset = 0
                    var pos = ifdOffset + 2
                    for (e in 0 until entries) {
                        if (pos + 12 > rawBytes.size) break
                        val tag = bb.getShort(pos).toInt() and 0xFFFF
                        val value = bb.getInt(pos + 8)
                        when (tag) {
                            256 -> w = value
                            257 -> h = value
                            273 -> stripOffset = value
                        }
                        pos += 12
                    }
                    if (w in 1..8192 && h in 1..8192 && stripOffset > 0 && stripOffset + w * h * 4 <= rawBytes.size) {
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val pixels = IntArray(w * h)
                        var bIdx = stripOffset
                        for (i in 0 until w * h) {
                            val r = rawBytes[bIdx++].toInt() and 0xFF
                            val g = rawBytes[bIdx++].toInt() and 0xFF
                            val b = rawBytes[bIdx++].toInt() and 0xFF
                            val a = rawBytes[bIdx++].toInt() and 0xFF
                            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
                        val proj = SketchProjectState(name = suggestedName, width = w, height = h, isDirty = false, isSavedOnce = true)
                        proj.layers.clear()
                        proj.layers.add(SketchLayer("layer_1", "Layer 1", bmp))
                        return proj
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun writeSketchbookMultiLayerTiff(
        bmp: Bitmap,
        project: SketchProjectState,
        out: OutputStream,
        logicalW: Int,
        logicalH: Int
    ) {
        val w = bmp.width
        val h = bmp.height
        val imageByteCount = w * h * 4
        val softwareStr = "Autodesk SketchBook ($logicalW x $logicalH)\u0000"
        val softwareBytes = softwareStr.toByteArray(Charsets.US_ASCII)
        val sblyChunkBytes = buildMultiLayerChunkBytes(project)

        val ifdOffset = 8
        val numEntries = 13
        val afterIfdOffset = ifdOffset + 2 + (numEntries * 12) + 4
        val bpsOffset = afterIfdOffset
        val xResOffset = bpsOffset + 8
        val yResOffset = xResOffset + 8
        val softOffset = yResOffset + 8
        val pixelDataOffset = softOffset + softwareBytes.size + (softwareBytes.size % 2)
        val sblyOffset = pixelDataOffset + imageByteCount

        val headerBuf = ByteBuffer.allocate(pixelDataOffset).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.put('I'.code.toByte())
        headerBuf.put('I'.code.toByte())
        headerBuf.putShort(42)
        headerBuf.putInt(ifdOffset)

        headerBuf.position(ifdOffset)
        headerBuf.putShort(numEntries.toShort())

        fun putIfdEntry(tag: Int, type: Int, count: Int, valueOrOffset: Int) {
            headerBuf.putShort(tag.toShort())
            headerBuf.putShort(type.toShort())
            headerBuf.putInt(count)
            headerBuf.putInt(valueOrOffset)
        }

        putIfdEntry(256, 4, 1, w)
        putIfdEntry(257, 4, 1, h)
        putIfdEntry(258, 3, 4, bpsOffset)
        putIfdEntry(259, 3, 1, 1)
        putIfdEntry(262, 3, 1, 2)
        putIfdEntry(273, 4, 1, pixelDataOffset)
        putIfdEntry(277, 3, 1, 4)
        putIfdEntry(278, 4, 1, h)
        putIfdEntry(279, 4, 1, imageByteCount)
        putIfdEntry(282, 5, 1, xResOffset)
        putIfdEntry(283, 5, 1, yResOffset)
        putIfdEntry(305, 2, softwareBytes.size, softOffset)
        putIfdEntry(TAG_SBLY_CHUNK, 7, sblyChunkBytes.size, sblyOffset)

        headerBuf.putInt(0)

        headerBuf.position(bpsOffset)
        headerBuf.putShort(8); headerBuf.putShort(8); headerBuf.putShort(8); headerBuf.putShort(8)

        headerBuf.position(xResOffset)
        headerBuf.putInt(300); headerBuf.putInt(1)
        headerBuf.position(yResOffset)
        headerBuf.putInt(300); headerBuf.putInt(1)

        headerBuf.position(softOffset)
        headerBuf.put(softwareBytes)

        val bos = BufferedOutputStream(out, 262144)
        bos.write(headerBuf.array())

        val chunkRows = min(64, h).coerceAtLeast(1)
        val chunkPixels = IntArray(w * chunkRows)
        val chunkBytes = ByteArray(w * chunkRows * 4)
        var y = 0
        while (y < h) {
            val rows = min(chunkRows, h - y)
            bmp.getPixels(chunkPixels, 0, w, 0, y, w, rows)
            val totalPx = w * rows
            var bPos = 0
            for (i in 0 until totalPx) {
                val c = chunkPixels[i]
                chunkBytes[bPos++] = ((c shr 16) and 0xFF).toByte()
                chunkBytes[bPos++] = ((c shr 8) and 0xFF).toByte()
                chunkBytes[bPos++] = (c and 0xFF).toByte()
                chunkBytes[bPos++] = ((c shr 24) and 0xFF).toByte()
            }
            bos.write(chunkBytes, 0, totalPx * 4)
            y += rows
        }

        // Write embedded SBLY Multi-Layer chunk at sblyOffset
        bos.write(sblyChunkBytes)
        bos.flush()
    }

    private fun saveTiffToPublicStorage(context: Context, srcFile: File, fileName: String): String {
        var savedLoc = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val dlValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/tiff")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SketchBook")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val dlUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
                if (dlUri != null) {
                    resolver.openOutputStream(dlUri)?.use { out ->
                        FileInputStream(srcFile).use { inp -> inp.copyTo(out) }
                    }
                    dlValues.clear()
                    dlValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(dlUri, dlValues, null, null)
                    savedLoc = "Download/SketchBook/$fileName"
                }
            } catch (_: Exception) {}

            try {
                val resolver = context.contentResolver
                val picValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/tiff")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SketchBook")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val picUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, picValues)
                if (picUri != null) {
                    resolver.openOutputStream(picUri)?.use { out ->
                        FileInputStream(srcFile).use { inp -> inp.copyTo(out) }
                    }
                    picValues.clear()
                    picValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(picUri, picValues, null, null)
                    if (savedLoc.isEmpty()) savedLoc = "Pictures/SketchBook/$fileName"
                }
            } catch (_: Exception) {}
            if (savedLoc.isNotEmpty()) return savedLoc
        }

        try {
            val pubDl = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SketchBook")
            if (!pubDl.exists()) pubDl.mkdirs()
            val dlFile = File(pubDl, fileName)
            FileInputStream(srcFile).use { inp -> FileOutputStream(dlFile).use { out -> inp.copyTo(out) } }
            savedLoc = "Download/SketchBook/$fileName"
        } catch (_: Exception) {}

        try {
            val pubPic = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SketchBook")
            if (!pubPic.exists()) pubPic.mkdirs()
            val picFile = File(pubPic, fileName)
            FileInputStream(srcFile).use { inp -> FileOutputStream(picFile).use { out -> inp.copyTo(out) } }
            if (savedLoc.isEmpty()) savedLoc = "Pictures/SketchBook/$fileName"
        } catch (_: Exception) {}

        if (savedLoc.isNotEmpty()) return savedLoc
        val fbDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "SketchBook")
        if (!fbDir.exists()) fbDir.mkdirs()
        val fbFile = File(fbDir, fileName)
        FileInputStream(srcFile).use { inp -> FileOutputStream(fbFile).use { out -> inp.copyTo(out) } }
        return fbFile.absolutePath
    }

    private fun saveImageToMediaStoreOrDisk(
        context: Context,
        bmp: Bitmap,
        baseName: String,
        ext: String,
        mimeType: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ): String {
        val fileName = "$baseName.$ext"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SketchBook")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    BufferedOutputStream(out, 131072).use { bos ->
                        bmp.compress(format, quality, bos)
                    }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "Pictures/SketchBook/$fileName"
            }
        }

        val pubPic = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SketchBook")
        if (!pubPic.exists()) pubPic.mkdirs()
        val outFile = File(pubPic, fileName)
        return try {
            BufferedOutputStream(FileOutputStream(outFile), 131072).use { out ->
                bmp.compress(format, quality, out)
            }
            outFile.absolutePath
        } catch (_: Exception) {
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "SketchBook")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val fbFile = File(fallbackDir, fileName)
            BufferedOutputStream(FileOutputStream(fbFile), 131072).use { out ->
                bmp.compress(format, quality, out)
            }
            fbFile.absolutePath
        }
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().ifEmpty { "Untitled-1" }
    }
}
