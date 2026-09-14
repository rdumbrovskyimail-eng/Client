// >>> FILE: app/src/main/java/com/client/app/util/AttachmentProcessor.kt
package com.client.app.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
        private const val JPEG_QUALITY = 88
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
                    // 1. Исходные файлы с кодом, разметкой и текстом: мгновенное чтение без расхода Vision-токенов
                    isTextFormat(mime, name) -> {
                        val txt = context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.readBytes().decodeToString()
                        }
                        if (!txt.isNullOrBlank()) {
                            textBuilder.append("\n\n--- Документ/Код: $name ---\n").append(txt.take(60_000))
                            accepted.add("$name (текстовый слой, ${txt.length} симв.)")
                        }
                    }

                    // 2. PDF-документы: гибридный цифровой/растровый разбор с переиспользованием битмапа
                    mime == "application/pdf" || name.endsWith(".pdf", true) -> {
                        val pdf = processPdfStream(uri, MAX_PDF_PAGES)
                        if (pdf.text.isNotBlank()) {
                            textBuilder.append("\n\n--- Документ: $name ---\n").append(pdf.text)
                        }
                        if (pdf.images.isNotEmpty()) {
                            images.addAll(pdf.images)
                        }
                        accepted.add("$name (${pdf.images.size} стр. OCR, ${pdf.text.length} симв.)")
                    }

                    // 3. Растровые изображения с аппаратной коррекцией EXIF-ориентации
                    mime.startsWith("image/") || isImageExtension(name) -> {
                        val jpegBytes = loadScaledJpeg(uri)
                        if (jpegBytes != null) {
                            images.add(jpegBytes)
                            accepted.add("$name (изображение)")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e("Attachment error: $name", e)
            }
        }
        Result(images, textBuilder.toString().trim(), accepted)
    }

    private fun isTextFormat(mime: String, name: String): Boolean {
        if (mime.startsWith("text/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "md", "json", "xml", "kt", "java", "py",
            "c", "cpp", "h", "hpp", "csv", "html", "css",
            "yaml", "yml", "gradle", "kts", "sql", "sh"
        )
    }

    private fun isImageExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic")
    }

    /**
     * Загружает изображение с масштабированием до MAX_SIDE и компенсацией EXIF-поворота.
     */
    private fun loadScaledJpeg(uri: Uri): ByteArray? {
        val cr = context.contentResolver

        // Определение угла поворота сенсора камеры
        val orientation = runCatching {
            cr.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        // Замер исходных габаритов без выделения памяти под пиксели
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longestSide / (sample * 2) >= MAX_SIDE) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val rawBmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return null

        val matrix = Matrix()
        if (rotationDegrees != 0f) {
            matrix.postRotate(rotationDegrees)
        }

        val longestDecoded = maxOf(rawBmp.width, rawBmp.height)
        if (longestDecoded > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longestDecoded
            matrix.postScale(scale, scale)
        }

        val finalBmp = if (!matrix.isIdentity) {
            Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true).also {
                if (it != rawBmp) rawBmp.recycle()
            }
        } else {
            rawBmp
        }

        val out = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        finalBmp.recycle()
        return out.toByteArray()
    }

    private data class PdfProcessed(val images: List<ByteArray>, val text: String)

    /**
     * Постраничный разбор PDF с выделением цифрового текстового слоя на Android 15+
     * и повторным использованием одного буфера кадра (Zero GC Thrashing) для графических страниц.
     */
    private fun processPdfStream(uri: Uri, maxPages: Int): PdfProcessed {
        val images = mutableListOf<ByteArray>()
        val textBuilder = StringBuilder()

        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return PdfProcessed(images, "")

        pfd.use {
            PdfRenderer(it).use { renderer ->
                val count = minOf(renderer.pageCount, maxPages)
                var reusableBmp: Bitmap? = null

                try {
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            var pageText = ""
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                                runCatching {
                                    pageText = page.textContents.joinToString(" ") { it.text }.trim()
                                }
                            }

                            // Если на странице обнаружен полноценный связный текст — не тратим время на растрирование
                            if (pageText.length >= 60) {
                                textBuilder.append("--- Стр. ").append(i + 1).append(" ---\n")
                                    .append(pageText).append("\n\n")
                            } else {
                                val scale = MAX_SIDE.toFloat() / maxOf(page.width, page.height)
                                val w = (page.width * scale).toInt().coerceAtLeast(1)
                                val h = (page.height * scale).toInt().coerceAtLeast(1)

                                if (reusableBmp == null || reusableBmp!!.width != w || reusableBmp!!.height != h) {
                                    reusableBmp?.recycle()
                                    reusableBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                }

                                val canvas = Canvas(reusableBmp!!)
                                canvas.drawColor(Color.WHITE)
                                page.render(reusableBmp!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                ByteArrayOutputStream().use { out ->
                                    reusableBmp!!.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                                    images.add(out.toByteArray())
                                }
                            }
                        }
                    }
                } finally {
                    reusableBmp?.recycle()
                }
            }
        }
        return PdfProcessed(images, textBuilder.toString().trim())
    }

    private fun getFileName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
}