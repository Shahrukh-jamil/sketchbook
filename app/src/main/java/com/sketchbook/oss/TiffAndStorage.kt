package com.sketchbook.oss

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ProjectStorageManager {

    private const val PREFS_NAME = "sketchbook_classic_prefs"
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

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

    private data class UniformFit(val scale: Float, val offX: Float, val offY: Float, val drawW: Float, val drawH: Float)

    private fun computeUniformFit(srcW: Int, srcH: Int, targetW: Int, targetH: Int): UniformFit {
        val sW = max(1, srcW).toFloat(); val sH = max(1, srcH).toFloat()
        val tW = max(1, targetW).toFloat(); val tH = max(1, targetH).toFloat()
        val scale = min(tW / sW, tH / sH)
        val drawW = sW * scale; val drawH = sH * scale
        val offX = (tW - drawW) * 0.5f; val offY = (tH - drawH) * 0.5f
        return UniformFit(scale, offX, offY, drawW, drawH)
    }

    fun renderCompositeFrame(
        targetBmp: Bitmap,
        layerBmp: Bitmap,
        paperColor: Int
    ) {
        val exactW = targetBmp.width
        val exactH = targetBmp.height
        val c = Canvas(targetBmp)
        c.drawColor(paperColor)
        if (layerBmp.width == exactW && layerBmp.height == exactH) {
            c.drawBitmap(layerBmp, 0f, 0f, null)
        } else {
            val fit = computeUniformFit(layerBmp.width, layerBmp.height, exactW, exactH)
            c.drawBitmap(layerBmp, null, RectF(fit.offX, fit.offY, fit.offX + fit.drawW, fit.offY + fit.drawH), filterPaint)
        }
    }

    fun saveProjectToDevice(
        context: Context,
        project: SketchProjectState,
        asCopy: Boolean,
        paperColor: Int = Color.WHITE
    ): Result<String> {
        return try {
            val safeBase = sanitizeFileName(project.name.ifBlank { "Untitled-1" })
            val finalName = if (asCopy) "${safeBase}_copy_${System.currentTimeMillis() % 10000}" else safeBase
            val exactW = project.width.coerceAtLeast(1)
            val exactH = project.height.coerceAtLeast(1)
            val layerBmp = project.ensureCanvasBitmap()
            val outBmp = Bitmap.createBitmap(exactW, exactH, Bitmap.Config.ARGB_8888)
            renderCompositeFrame(outBmp, layerBmp, paperColor)
            val savedPngLocation = saveImageToMediaStoreOrDisk(
                context, outBmp, finalName, "png", "image/png", Bitmap.CompressFormat.PNG, 100
            )
            val tiffDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects")
            if (!tiffDir.exists()) tiffDir.mkdirs()
            val tiffFile = File(tiffDir, "$finalName.tif")
            FileOutputStream(tiffFile).use { fos -> writeSketchbookTiff(outBmp, fos, exactW, exactH) }
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
            val safeBase = sanitizeFileName(project.name.ifBlank { "Untitled-1" })
            val exactW = project.width.coerceAtLeast(1)
            val exactH = project.height.coerceAtLeast(1)
            val layerBmp = project.ensureCanvasBitmap()
            val outBmp = Bitmap.createBitmap(exactW, exactH, Bitmap.Config.ARGB_8888)
            renderCompositeFrame(outBmp, layerBmp, paperColor)
            val loc = when (formatCode.uppercase()) {
                "JPG", "JPEG" -> saveImageToMediaStoreOrDisk(
                    context, outBmp, safeBase, "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 96
                )
                "TIF", "TIFF" -> {
                    val tiffBytes = File(context.cacheDir, "export_${System.currentTimeMillis()}.tif")
                    FileOutputStream(tiffBytes).use { fos -> writeSketchbookTiff(outBmp, fos, exactW, exactH) }
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

    fun writeSketchbookTiff(bmp: Bitmap, out: OutputStream, logicalW: Int, logicalH: Int) {
        val w = bmp.width
        val h = bmp.height
        val imageByteCount = w * h * 4
        val softwareStr = "Autodesk SketchBook ($logicalW x $logicalH)\u0000"
        val softwareBytes = softwareStr.toByteArray(Charsets.US_ASCII)

        val ifdOffset = 8
        val numEntries = 12
        val afterIfdOffset = ifdOffset + 2 + (numEntries * 12) + 4
        val bpsOffset = afterIfdOffset
        val xResOffset = bpsOffset + 8
        val yResOffset = xResOffset + 8
        val softOffset = yResOffset + 8
        val pixelDataOffset = softOffset + softwareBytes.size + (softwareBytes.size % 2)

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
        bos.flush()
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().ifEmpty { "Untitled-1" }
    }
}
