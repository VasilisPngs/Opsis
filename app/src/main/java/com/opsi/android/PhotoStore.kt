package com.opsi.android

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.heifwriter.HeifWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PhotoFormat { HEIC, JPEG }

enum class FrameRatio(val widthOverHeight: Float, val label: String) {
    RATIO_4_3(3f / 4f, "4:3"),
    RATIO_16_9(9f / 16f, "16:9"),
    RATIO_1_1(1f, "1:1")
}

fun Context.findActivity(): ComponentActivity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    throw IllegalStateException("No ComponentActivity in context chain")
}

fun isHeicEncodingSupported(): Boolean {
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    return codecs.codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { type ->
            type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) ||
                type.equals("image/vnd.android.heic", ignoreCase = true)
        }
    }
}

fun rotateUpright(source: Bitmap, rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return source
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

fun mirrorHorizontally(source: Bitmap): Bitmap {
    val matrix = Matrix().apply { postScale(-1f, 1f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

fun cropToRatio(source: Bitmap, widthOverHeight: Float): Bitmap {
    val sourceRatio = source.width.toFloat() / source.height.toFloat()
    if (abs(sourceRatio - widthOverHeight) < 0.001f) return source
    return if (sourceRatio > widthOverHeight) {
        val targetWidth = (source.height * widthOverHeight).roundToInt().coerceIn(1, source.width)
        val left = ((source.width - targetWidth) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(source, left, 0, targetWidth, source.height)
    } else {
        val targetHeight = (source.width / widthOverHeight).roundToInt().coerceIn(1, source.height)
        val top = ((source.height - targetHeight) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(source, 0, top, source.width, targetHeight)
    }
}

private fun ensureEvenDimensions(source: Bitmap): Bitmap {
    val width = source.width - (source.width % 2)
    val height = source.height - (source.height % 2)
    if (width == source.width && height == source.height) return source
    return Bitmap.createBitmap(source, 0, 0, width.coerceAtLeast(2), height.coerceAtLeast(2))
}

private fun timestampName(extension: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    return "Opsi_$stamp.$extension"
}

private fun encodeHeicToFile(bitmap: Bitmap, target: File, quality: Int) {
    val writer = HeifWriter.Builder(target.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
        .setQuality(quality)
        .setMaxImages(1)
        .build()
    writer.start()
    writer.addBitmap(bitmap)
    writer.stop(0)
    writer.close()
}

private fun encodeJpegToFile(bitmap: Bitmap, target: File, quality: Int) {
    FileOutputStream(target).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
    }
}

private fun publishToGallery(context: Context, source: File, displayName: String, mimeType: String): Uri {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
        ?: throw IllegalStateException("MediaStore insert failed")
    resolver.openOutputStream(uri)?.use { output ->
        source.inputStream().use { input -> input.copyTo(output) }
    } ?: throw IllegalStateException("Unable to open output stream")
    val finalize = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
    resolver.update(uri, finalize, null, null)
    return uri
}

fun capturePhoto(context: Context, bitmap: Bitmap, format: PhotoFormat, quality: Int = 100): Uri {
    val useHeic = format == PhotoFormat.HEIC && isHeicEncodingSupported()
    val extension = if (useHeic) "heic" else "jpg"
    val mimeType = if (useHeic) "image/heic" else "image/jpeg"
    val displayName = timestampName(extension)
    val prepared = ensureEvenDimensions(bitmap)
    val temp = File.createTempFile("capture_", ".$extension", context.cacheDir)
    try {
        if (useHeic) encodeHeicToFile(prepared, temp, quality) else encodeJpegToFile(prepared, temp, quality)
        return publishToGallery(context, temp, displayName, mimeType)
    } finally {
        temp.delete()
    }
}

fun loadGalleryThumbnail(context: Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
    } catch (e: Exception) {
        null
    }
}

fun openInGallery(context: Context, uri: Uri) {
    val mimeType = context.contentResolver.getType(uri) ?: "image/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
