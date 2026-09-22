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
import java.io.InputStreamReader
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
        private const val MAX_TEXT_CHARS_PER_FILE = 60_000
    }

    suspend fun process(uris: List<Uri>): Result =
        withContext(Dispatchers.IO) {
            val images = mutableListOf<ByteArray>()
            val accepted = mutableListOf<String>()
            val textBuilder = StringBuilder()

            for (uri in uris) {
                val name = getFileName(uri)
                val mime =
                    context.contentResolver
                        .getType(uri)
                        .orEmpty()
                        .lowercase()

                try {
                    when {
                        // 1. Исходные файлы с кодом, разметкой и текстом:
                        //    ограниченное потоковое чтение без полного readBytes().
                        isTextFormat(mime, name) -> {
                            val txt =
                                context.contentResolver
                                    .openInputStream(uri)
                                    ?.use { stream ->
                                        InputStreamReader(
                                            stream,
                                            Charsets.UTF_8
                                        ).use { reader ->
                                            val buffer =
                                                CharArray(8192)

                                            val out =
                                                StringBuilder(
                                                    MAX_TEXT_CHARS_PER_FILE
                                                )

                                            while (
                                                out.length <
                                                MAX_TEXT_CHARS_PER_FILE
                                            ) {
                                                val toRead =
                                                    minOf(
                                                        buffer.size,
                                                        MAX_TEXT_CHARS_PER_FILE -
                                                            out.length
                                                    )

                                                val count =
                                                    reader.read(
                                                        buffer,
                                                        0,
                                                        toRead
                                                    )

                                                if (count <= 0) {
                                                    break
                                                }

                                                out.append(
                                                    buffer,
                                                    0,
                                                    count
                                                )
                                            }

                                            out.toString()
                                        }
                                    }

                            if (!txt.isNullOrBlank()) {
                                textBuilder
                                    .append(
                                        "\n\n--- Документ/Код: "
                                    )
                                    .append(name)
                                    .append(" ---\n")
                                    .append(txt)

                                accepted.add(
                                    "$name (текстовый слой, " +
                                        "${txt.length} симв." +
                                        if (
                                            txt.length ==
                                            MAX_TEXT_CHARS_PER_FILE
                                        ) {
                                            ", обрезан"
                                        } else {
                                            ""
                                        } +
                                        ")"
                                )
                            }
                        }

                        // 2. PDF-документы:
                        //    цифровой text layer извлекается отдельно;
                        //    страницы без достаточного text layer
                        //    только рендерятся в изображения.
                        mime == "application/pdf" ||
                            name.endsWith(".pdf", true) -> {

                            val pdf =
                                processPdfStream(
                                    uri,
                                    MAX_PDF_PAGES
                                )

                            if (pdf.text.isNotBlank()) {
                                textBuilder
                                    .append(
                                        "\n\n--- Документ: "
                                    )
                                    .append(name)
                                    .append(" ---\n")
                                    .append(pdf.text)
                            }

                            if (pdf.images.isNotEmpty()) {
                                images.addAll(pdf.images)
                            }

                            /*
                             * Important: these pages are rendered raster
                             * images, not OCR results. OCR is not performed
                             * by AttachmentProcessor.
                             */
                            val rasterCount =
                                pdf.images.size

                            val textCharCount =
                                pdf.text.length

                            accepted.add(
                                "$name (" +
                                    "$rasterCount растровых страниц, " +
                                    "$textCharCount симв. текстового слоя)"
                            )
                        }

                        // 3. Растровые изображения с аппаратной
                        //    коррекцией EXIF-ориентации.
                        mime.startsWith("image/") ||
                            isImageExtension(name) -> {

                            val jpegBytes =
                                loadScaledJpeg(uri)

                            if (jpegBytes != null) {
                                images.add(jpegBytes)

                                accepted.add(
                                    "$name (изображение)"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }

                    logger.e(
                        "Attachment error: $name",
                        e
                    )
                }
            }

            Result(
                images = images,
                extractedText = textBuilder
                    .toString()
                    .trim(),
                accepted = accepted
            )
        }

    private fun isTextFormat(
        mime: String,
        name: String
    ): Boolean {
        if (mime.startsWith("text/")) {
            return true
        }

        val ext =
            name
                .substringAfterLast('.', "")
                .lowercase()

        return ext in setOf(
            "txt",
            "md",
            "json",
            "xml",
            "kt",
            "java",
            "py",
            "c",
            "cpp",
            "h",
            "hpp",
            "csv",
            "html",
            "css",
            "yaml",
            "yml",
            "gradle",
            "kts",
            "sql",
            "sh"
        )
    }

    private fun isImageExtension(
        name: String
    ): Boolean {
        val ext =
            name
                .substringAfterLast('.', "")
                .lowercase()

        return ext in setOf(
            "jpg",
            "jpeg",
            "png",
            "webp",
            "bmp",
            "heic"
        )
    }

    /**
     * Загружает изображение с масштабированием до MAX_SIDE
     * и компенсацией EXIF-поворота.
     */
    private fun loadScaledJpeg(
        uri: Uri
    ): ByteArray? {
        val cr = context.contentResolver

        // Определение ориентации сенсора камеры.
        val orientation =
            runCatching {
                cr.openInputStream(uri)?.use { stream ->
                    val exif =
                        ExifInterface(stream)

                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }.getOrNull()
                ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.setScale(-1f, 1f)

            ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.setRotate(180f)

            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.setScale(1f, -1f)

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setScale(-1f, 1f)
                matrix.postRotate(270f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.setRotate(90f)

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setScale(-1f, 1f)
                matrix.postRotate(90f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.setRotate(270f)
        }

        // Замер исходных габаритов без выделения памяти
        // под пиксели.
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                bounds
            )
        }

        if (
            bounds.outWidth <= 0 ||
            bounds.outHeight <= 0
        ) {
            return null
        }

        var sample = 1

        val longestSide =
            maxOf(
                bounds.outWidth,
                bounds.outHeight
            )

        while (
            longestSide /
                (sample * 2) >=
                MAX_SIDE
        ) {
            sample *= 2
        }

        val decodeOpts =
            BitmapFactory.Options().apply {
                inSampleSize = sample
            }

        val rawBmp =
            cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    decodeOpts
                )
            } ?: return null

        val longestDecoded =
            maxOf(
                rawBmp.width,
                rawBmp.height
            )

        if (longestDecoded > MAX_SIDE) {
            val scale =
                MAX_SIDE.toFloat() /
                    longestDecoded

            matrix.postScale(
                scale,
                scale
            )
        }

        val finalBmp =
            if (!matrix.isIdentity) {
                Bitmap.createBitmap(
                    rawBmp,
                    0,
                    0,
                    rawBmp.width,
                    rawBmp.height,
                    matrix,
                    true
                ).also {
                    if (it != rawBmp) {
                        rawBmp.recycle()
                    }
                }
            } else {
                rawBmp
            }

        val out =
            ByteArrayOutputStream()

        val compressed =
            runCatching {
                finalBmp.compress(
                    Bitmap.CompressFormat.JPEG,
                    JPEG_QUALITY,
                    out
                )
            }.getOrDefault(false)

        finalBmp.recycle()

        if (!compressed) {
            logger.w(
                "AttachmentProcessor: " +
                    "JPEG compression failed for $uri"
            )
            return null
        }

        return out.toByteArray()
    }

    private data class PdfProcessed(
        val images: List<ByteArray>,
        val text: String
    )

    /**
     * Постраничный разбор PDF:
     *
     * 1. цифровой текстовый слой извлекается отдельно;
     * 2. если связного текста недостаточно, страница
     *    рендерится в растровое изображение;
     * 3. один Bitmap повторно используется между страницами
     *    одинакового размера;
     * 4. OCR здесь НЕ выполняется.
     */
    private fun processPdfStream(
        uri: Uri,
        maxPages: Int
    ): PdfProcessed {

        val images =
            mutableListOf<ByteArray>()

        val textBuilder =
            StringBuilder()

        val pfd: ParcelFileDescriptor =
            context.contentResolver
                .openFileDescriptor(
                    uri,
                    "r"
                )
                ?: return PdfProcessed(
                    images,
                    ""
                )

        pfd.use {
            PdfRenderer(it).use { renderer ->

                val count =
                    minOf(
                        renderer.pageCount,
                        maxPages
                    )

                var reusableBmp: Bitmap? = null

                try {
                    for (i in 0 until count) {
                        renderer
                            .openPage(i)
                            .use { page ->

                                var pageText = ""

                                if (
                                    Build.VERSION.SDK_INT >=
                                    Build.VERSION_CODES.VANILLA_ICE_CREAM
                                ) {
                                    runCatching {
                                        pageText =
                                            page.textContents
                                                .joinToString(" ") {
                                                    it.text
                                                }
                                                .trim()
                                    }
                                }

                                /*
                                 * A sufficiently populated digital text
                                 * layer is kept as text and the page is not
                                 * rasterized.
                                 *
                                 * Otherwise the page is only rendered as
                                 * an image for subsequent vision/model
                                 * processing. No OCR engine runs here.
                                 */
                                if (
                                    pageText.length >= 60
                                ) {
                                    textBuilder
                                        .append(
                                            "--- Стр. "
                                        )
                                        .append(
                                            i + 1
                                        )
                                        .append(
                                            " ---\n"
                                        )
                                        .append(
                                            pageText
                                        )
                                        .append(
                                            "\n\n"
                                        )
                                } else {
                                    val scale =
                                        MAX_SIDE.toFloat() /
                                            maxOf(
                                                page.width,
                                                page.height
                                            )

                                    val w =
                                        (
                                            page.width *
                                                scale
                                            ).toInt()
                                            .coerceAtLeast(1)

                                    val h =
                                        (
                                            page.height *
                                                scale
                                            ).toInt()
                                            .coerceAtLeast(1)

                                    if (
                                        reusableBmp == null ||
                                        reusableBmp!!.width != w ||
                                        reusableBmp!!.height != h
                                    ) {
                                        reusableBmp?.recycle()

                                        reusableBmp =
                                            Bitmap.createBitmap(
                                                w,
                                                h,
                                                Bitmap.Config.ARGB_8888
                                            )
                                    }

                                    val canvas =
                                        Canvas(
                                            reusableBmp!!
                                        )

                                    canvas.drawColor(
                                        Color.WHITE
                                    )

                                    page.render(
                                        reusableBmp!!,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                    )

                                    ByteArrayOutputStream().use { out ->
                                        val compressed =
                                            runCatching {
                                                reusableBmp!!.compress(
                                                    Bitmap.CompressFormat.JPEG,
                                                    JPEG_QUALITY,
                                                    out
                                                )
                                            }.getOrDefault(false)

                                        if (compressed) {
                                            images.add(
                                                out.toByteArray()
                                            )
                                        } else {
                                            logger.w(
                                                "AttachmentProcessor: " +
                                                    "JPEG compression failed " +
                                                    "for PDF page ${i + 1}"
                                            )
                                        }
                                    }
                                }
                            }
                    }
                } finally {
                    reusableBmp?.recycle()
                }
            }
        }

        return PdfProcessed(
            images = images,
            text = textBuilder
                .toString()
                .trim()
        )
    }

    private fun getFileName(
        uri: Uri
    ): String =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else {
                    null
                }
            }
        }.getOrNull()
            ?: uri.lastPathSegment
            ?: "file"
}