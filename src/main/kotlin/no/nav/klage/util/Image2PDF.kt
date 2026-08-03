package no.nav.klage.util

import no.nav.klage.exceptions.AttachmentCouldNotBeConvertedException
import no.nav.klage.getLogger
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.tika.Tika
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import org.springframework.util.unit.DataUnit
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * Detects the actual type of an uploaded file (by content, not by declared header) and, if it is a
 * supported image, converts it into a single-page A4 PDF in place. Files that already are PDFs pass
 * through unchanged. Any other type is rejected.
 *
 * Supported images: JPEG, PNG and TIFF (decoded via ImageIO).
 */
@Component
class Image2PDF {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)

        private const val PDF = MediaType.APPLICATION_PDF_VALUE
        private val IMAGEIO_TYPES = setOf(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/tiff",
        )
    }

    private val A4: PDRectangle = PDRectangle.A4

    data class ConversionResult(
        val file: File,
        val contentType: String,
        val wasConverted: Boolean,
        val originalContentType: String,
    )

    fun convertIfImage(file: File): ConversionResult {
        val detectedType = detectContentType(file)

        if (PDF == detectedType) {
            logger.debug("File is already a PDF")
            return ConversionResult(
                file = file,
                contentType = PDF,
                wasConverted = false,
                originalContentType = detectedType,
            )
        }

        if (detectedType !in IMAGEIO_TYPES) {
            val exception = AttachmentCouldNotBeConvertedException()
            logger.warn("User tried to upload an unsupported file type: $detectedType", exception)
            throw exception
        }

        logger.debug("Converting file of type {} to PDF", detectedType)

        embedImageInPDF(source = file, target = file)

        return ConversionResult(
            file = file,
            contentType = PDF,
            wasConverted = true,
            originalContentType = detectedType,
        )
    }

    private fun detectContentType(file: File): String {
        val bytesForFiletypeDetection =
            file.inputStream().use {
                it.readNBytes(min(DataSize.of(3, DataUnit.KILOBYTES).toBytes().toInt(), file.length().toInt()))
            }
        return Tika().detect(bytesForFiletypeDetection)
    }

    private fun embedImageInPDF(source: File, target: File) {
        try {
            val image = ImageIO.read(source) ?: throw AttachmentCouldNotBeConvertedException()
            val scaledImage = ImageUtils.scaleToA4(image)

            PDDocument().use { doc ->
                val page = PDPage(A4)
                doc.addPage(page)
                val xImage: PDImageXObject = LosslessFactory.createFromImage(doc, scaledImage)
                PDPageContentStream(doc, page).use { contentStream ->
                    contentStream.drawImage(xImage, A4.lowerLeftX, A4.lowerLeftY)
                }
                doc.save(target)
            }
        } catch (ex: AttachmentCouldNotBeConvertedException) {
            throw ex
        } catch (ex: Exception) {
            throw RuntimeException("Conversion of attachment failed", ex)
        }
    }
}
