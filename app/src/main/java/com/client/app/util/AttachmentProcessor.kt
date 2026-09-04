// >>> FILE: app/src/main/java/com/client/app/util/AttachmentProcessor.kt
package com.client.app.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    data class Result(
        val images: List<ByteArray>,
        val extractedText: String,
        val accepted: List<String>
    )

    companion object {
        private const val MAX_SIDE = 1568
        private const val JPEG_QUALITY = 90
        private const val MAX_PDF_PAGES = 16
    }

    suspend fun process(uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        val images = mutableListOf<ByteArray>()
        val accepted = mutableListOf<String>()
        val textBuilder = StringBuilder()

        for (uri in uris) {
            val name = getFileName(uri)
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase()

            try {
                when {
                    mime.startsWith("image/") -> {
                        loadScaledJpeg(uri)?.let {
                            images.add(it)
                            accepted.add(name)
                        }
                    }
                    mime == "application/pdf" || name.endsWith(".pdf", true) -> {
                        val (rendered, totalPages) = renderPdf(uri, MAX_PDF_PAGES)
                        if (rendered.isNotEmpty()) {
                            images.addAll(rendered)
                            val label = if (totalPages > rendered.size) {
                                "$name (первые ${rendered.size} из $totalPages стр.)"
                            } else {
                                "$name (${rendered.size} стр.)"
                            }
                            accepted.add(label)
                        }
                    }
                    isTextFormat(mime, name) -> {
                        val txt = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        if (!txt.isNullOrBlank()) {
                            textBuilder.append("\n\n--- Файл: $name ---\n").append(txt.take(40000))
                            accepted.add(name)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.e("Attachment error: $name", e)
            }
        }
        Result(images, textBuilder.toString().trim(), accepted)
    }

    private fun getFileName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"

    private fun isTextFormat(mime: String, name: String): Boolean {
        if (mime.startsWith("text/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("txt", "md", "json", "xml", "kt", "java", "py", "c", "cpp", "csv", "html", "css", "yaml", "yml")
    }

    private fun loadScaledJpeg(uri: Uri): ByteArray? {
        val cr = context.contentResolver

        val orientation = runCatching {
            cr.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val matrix = Matrix()
        if (rotationDegrees != 0f) matrix.postRotate(rotationDegrees)

        val longestDecoded = maxOf(src.width, src.height)
        if (longestDecoded > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longestDecoded
            matrix.postScale(scale, scale)
        }

        val resultBmp = if (!matrix.isIdentity) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true).also {
                if (it != src) src.recycle()
            }
        } else src

        val out = ByteArrayOutputStream()
        resultBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        resultBmp.recycle()
        return out.toByteArray()
    }

    private fun renderPdf(uri: Uri, maxPages: Int): Pair<List<ByteArray>, Int> {
        val list = mutableListOf<ByteArray>()
        var totalPages = 0
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return Pair(list, 0)
        pfd.use {
            PdfRenderer(it).use { renderer ->
                totalPages = renderer.pageCount
                val count = minOf(totalPages, maxPages)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val scale = MAX_SIDE.toFloat() / maxOf(page.width, page.height)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        bmp.recycle()
                        list.add(out.toByteArray())
                    }
                }
            }
        }
        return Pair(list, totalPages)
    }
}