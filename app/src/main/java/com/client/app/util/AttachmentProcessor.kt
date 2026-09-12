// >>> FILE: app/src/main/java/com/client/app/util/AttachmentProcessor.kt
package com.client.app.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
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
                if (mime == "application/pdf" || name.endsWith(".pdf", true)) {
                    val pdf = processPdfStream(uri, MAX_PDF_PAGES)
                    if (pdf.text.isNotBlank()) {
                        textBuilder.append("\n\n--- Документ: $name ---\n").append(pdf.text.take(60000))
                        accepted.add("$name (текст)")
                    } else if (pdf.images.isNotEmpty()) {
                        images.addAll(pdf.images)
                        accepted.add("$name (${pdf.images.size} стр.)")
                    }
                }
            } catch (e: Exception) {
                logger.e("Attachment error: $name", e)
            }
        }
        Result(images, textBuilder.toString().trim(), accepted)
    }

    private data class PdfProcessed(val images: List<ByteArray>, val text: String)

    /**
     * Потоковая обработка PDF: ровно 1 переиспользуемый Bitmap в RGB_565 (память <5 МБ вместо 157 МБ).
     */
    private fun processPdfStream(uri: Uri, maxPages: Int): PdfProcessed {
        val images = mutableListOf<ByteArray>()
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return PdfProcessed(images, "")

        pfd.use {
            PdfRenderer(it).use { renderer ->
                val count = minOf(renderer.pageCount, maxPages)

                // Проверка цифрового слоя текста на Android 15+ (API 35+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    val sb = StringBuilder()
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            runCatching {
                                val pageText = page.textContents.joinToString(" ") { tc -> tc.text }.trim()
                                if (pageText.isNotEmpty()) {
                                    sb.append("--- Стр. ").append(i + 1).append(" ---\n").append(pageText).append("\n\n")
                                }
                            }
                        }
                    }
                    if (sb.length > count * 50) {
                        return PdfProcessed(images, sb.toString().trim())
                    }
                }

                // Поточный рендеринг: один Bitmap, переиспользуемый между страницами
                var reusableBmp: Bitmap? = null
                try {
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            val scale = MAX_SIDE.toFloat() / maxOf(page.width, page.height)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)

                            if (reusableBmp == null || reusableBmp!!.width != w || reusableBmp!!.height != h) {
                                reusableBmp?.recycle()
                                reusableBmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
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
                } finally {
                    reusableBmp?.recycle()
                }
            }
        }
        return PdfProcessed(images, "")
    }

    private fun getFileName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
}