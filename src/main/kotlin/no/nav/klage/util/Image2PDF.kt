package no.nav.klage.util

import no.nav.klage.exceptions.AttachmentConversionFailedException
import no.nav.klage.exceptions.AttachmentUnsupportedTypeException
import no.nav.klage.getLogger
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.util.Matrix
import org.apache.tika.Tika
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import org.springframework.util.unit.DataUnit
import java.awt.geom.AffineTransform
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

    data class FileTypeInfo(
        val detectedContentType: String,
        val requiresConversion: Boolean,
    )

    /**
     * Determines the actual type of [file] and whether it has to be converted to PDF, rejecting any
     * type we cannot turn into a PDF. Lets the caller know up front what work is coming.
     */
    fun inspect(file: File): FileTypeInfo {
        val detectedType = detectContentType(file)

        if (PDF != detectedType && detectedType !in IMAGEIO_TYPES) {
            val exception = AttachmentUnsupportedTypeException()
            logger.warn("User tried to upload an unsupported file type: $detectedType", exception)
            throw exception
        }

        return FileTypeInfo(
            detectedContentType = detectedType,
            requiresConversion = PDF != detectedType,
        )
    }

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
            val exception = AttachmentUnsupportedTypeException()
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
            PDDocument().use { doc ->
                val xImage = createImageXObject(doc, source)

                val page = PDPage(A4)
                doc.addPage(page)

                PDPageContentStream(doc, page).use { contentStream ->
                    contentStream.drawImage(xImage, placementMatrix(xImage.width, xImage.height))
                }
                doc.save(target)
            }
        } catch (ex: AttachmentUnsupportedTypeException) {
            throw ex
        } catch (ex: AttachmentConversionFailedException) {
            throw ex
        } catch (ex: Exception) {
            throw AttachmentConversionFailedException("Conversion of attachment failed", ex)
        }
    }

    /**
     * Embeds the image without needlessly degrading it: JPEGs are passed through byte for byte so the
     * original DCT data is preserved, everything else is embedded losslessly (Flate) at its original
     * resolution, capped at [ImageUtils.MAX_DPI].
     */
    private fun createImageXObject(doc: PDDocument, source: File): PDImageXObject {
        if (MediaType.IMAGE_JPEG_VALUE == detectContentType(source)) {
            try {
                return source.inputStream().use { JPEGFactory.createFromStream(doc, it) }
            } catch (ex: Exception) {
                logger.debug("Could not embed JPEG as-is, falling back to re-encoding", ex)
            }
        }

        val image = ImageIO.read(source)
            ?: throw AttachmentConversionFailedException("ImageIO could not decode file despite matching content type — file may be corrupt")
        return LosslessFactory.createFromImage(doc, ImageUtils.capResolution(image))
    }

    /**
     * Places the image on a portrait A4 page, scaled to fit and centered. Landscape images are rotated
     * 90 degrees by the matrix rather than by resampling the pixels, which keeps the quality intact.
     */
    private fun placementMatrix(imageWidth: Int, imageHeight: Int): Matrix {
        val rotate = imageWidth > imageHeight

        //Width and height of the page area the image gets to occupy, in the image's own orientation.
        val availableWidth = if (rotate) A4.height else A4.width
        val availableHeight = if (rotate) A4.width else A4.height

        val scale = min(availableWidth / imageWidth, availableHeight / imageHeight)
        val drawWidth = imageWidth * scale
        val drawHeight = imageHeight * scale

        //Size actually taken up on the page once the rotation is applied.
        val onPageWidth = if (rotate) drawHeight else drawWidth
        val onPageHeight = if (rotate) drawWidth else drawHeight
        val offsetX = (A4.width - onPageWidth) / 2
        val offsetY = (A4.height - onPageHeight) / 2

        val transform = AffineTransform()
        if (rotate) {
            //A clockwise 90 degree rotation moves the drawn box into negative y, so shift it back up
            //by its rotated height before centering.
            transform.translate(offsetX.toDouble(), (offsetY + onPageHeight).toDouble())
            transform.rotate(Math.toRadians(-90.0))
        } else {
            transform.translate(offsetX.toDouble(), offsetY.toDouble())
        }
        transform.scale(drawWidth.toDouble(), drawHeight.toDouble())
        return Matrix(transform)
    }
}
