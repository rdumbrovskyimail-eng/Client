// >>> FILE: app/src/main/java/com/client/app/util/AttachmentProcessor.kt
package com.client.app.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
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
                // E-16 & E-31: Поддержка как PDF, так и растровых изображений
                if (mime == "application/pdf" || name.endsWith(".pdf", true)) {
                    val pdf = processPdfStream(uri, MAX_PDF_PAGES)
                    if (pdf.text.isNotBlank()) {
                        textBuilder.append("\n\n--- Документ: $name ---\n").append(pdf.text)
                    }
                    if (pdf.images.isNotEmpty()) {
                        images.addAll(pdf.images)
                    }
                    accepted.add("$name (${pdf.images.size} стр. OCR, ${pdf.text.length} симв.)")
                } else if (mime.startsWith("image/") || name.endsWith(".jpg", true) || name.endsWith(".png", true)) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            val scaled = scaleBitmap(bmp, MAX_SIDE)
                            ByteArrayOutputStream().use { out ->
                                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                                images.add(out.toByteArray())
                            }
                            if (scaled != bmp) scaled.recycle()
                            bmp.recycle()
                            accepted.add("$name (изображение)")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.e("Attachment error: $name", e)
            }
        }
        Result(images, textBuilder.toString().trim(), accepted)
    }

    private data class PdfProcessed(val images: List<ByteArray>, val text: String)

    // E-16: Постраничный гибридный процессинг: текст страницы -> text, скан страницы -> image
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

    private fun scaleBitmap(bmp: Bitmap, maxSide: Int): Bitmap {
        val maxDim = maxOf(bmp.width, bmp.height)
        if (maxDim <= maxSide) return bmp
        val scale = maxSide.toFloat() / maxDim
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    }

    private fun getFileName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
}