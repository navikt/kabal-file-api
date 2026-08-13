package no.nav.klage.util

import no.nav.klage.exceptions.AttachmentConversionFailedException
import no.nav.klage.exceptions.AttachmentUnsupportedTypeException
import no.nav.klage.getLogger
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.util.Matrix
import org.apache.tika.Tika
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import org.springframework.util.unit.DataUnit
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import kotlin.math.max
import kotlin.math.min

/**
 * Detects the actual type of an uploaded file (by content, not by declared header) and, if it is a
 * supported image, converts it into a PDF in place. Files that already are PDFs pass through
 * unchanged. Any other type is rejected.
 *
 * Supported images: JPEG, PNG and TIFF (decoded via ImageIO). Multi page TIFFs become one A4 page per
 * image in the file.
 *
 * Memory: an uploaded image can be far larger than the pod's memory limit once decoded (a 20000x14000
 * scan is over 1 GB as ARGB), so the conversion never holds a full resolution decode in heap. Images
 * are decoded subsampled straight to roughly their final size, one page at a time, and PDFBox spills
 * the document's streams to a temp file instead of buffering them on the heap.
 */
@Component
class Image2PDF(
    /**
     * Conversions are memory heavy relative to the rest of the service, and Tomcat will happily run
     * one per request thread. Bounding them keeps the worst case peak predictable.
     */
    @Value($$"${conversion.max-concurrent:2}")
    maxConcurrentConversions: Int,
    @Value($$"${conversion.permit-timeout-seconds:120}")
    private val permitTimeoutSeconds: Long,
) {

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

    private val conversionPermits = Semaphore(max(1, maxConcurrentConversions))

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

        withConversionPermit {
            embedImageInPDF(source = file, target = file, detectedType = detectedType)
        }

        return ConversionResult(
            file = file,
            contentType = PDF,
            wasConverted = true,
            originalContentType = detectedType,
        )
    }

    private fun <T> withConversionPermit(block: () -> T): T {
        if (!conversionPermits.tryAcquire(permitTimeoutSeconds, TimeUnit.SECONDS)) {
            throw AttachmentConversionFailedException("Timed out waiting for a conversion slot")
        }
        try {
            return block()
        } finally {
            conversionPermits.release()
        }
    }

    private fun detectContentType(file: File): String {
        val bytesForFiletypeDetection =
            file.inputStream().use {
                it.readNBytes(min(DataSize.of(3, DataUnit.KILOBYTES).toBytes().toInt(), file.length().toInt()))
            }
        return Tika().detect(bytesForFiletypeDetection)
    }

    private fun embedImageInPDF(source: File, target: File, detectedType: String) {
        //[source] and [target] are usually the same file, so the PDF is written next to it and moved
        //into place only once it is complete. That also avoids destroying the upload on a failed save.
        val scratchPdf = Files.createTempFile(source.toPath().parent, "convert-", ".pdf")
        try {
            //A temp file backed stream cache keeps the compressed image data and the document itself
            //off the heap; without it a large document is buffered in memory twice over.
            PDDocument(IOUtils.createTempFileOnlyStreamCache()).use { doc ->
                if (!(MediaType.IMAGE_JPEG_VALUE == detectedType && tryEmbedJpegAsIs(doc, source))) {
                    embedPages(doc, source)
                }
                doc.save(scratchPdf.toFile())
            }
            Files.move(scratchPdf, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (ex: AttachmentUnsupportedTypeException) {
            throw ex
        } catch (ex: AttachmentConversionFailedException) {
            throw ex
        } catch (ex: Exception) {
            throw AttachmentConversionFailedException("Conversion of attachment failed", ex)
        } finally {
            Files.deleteIfExists(scratchPdf)
        }
    }

    /**
     * Passes a JPEG through byte for byte so the original DCT data is preserved, and so the image is
     * never decoded into heap at all. Returns false if the JPEG cannot be embedded as-is, in which
     * case the caller falls back to decoding it.
     */
    private fun tryEmbedJpegAsIs(doc: PDDocument, source: File): Boolean {
        return try {
            val xImage = source.inputStream().use { JPEGFactory.createFromStream(doc, it) }
            drawOnNewPage(doc, xImage)
            true
        } catch (ex: Exception) {
            logger.debug("Could not embed JPEG as-is, falling back to re-encoding", ex)
            false
        }
    }

    /**
     * Adds one A4 page per image in the file. Multi page TIFFs are common for scanned documents, and
     * decoding them one page at a time means the peak memory is set by the largest single page rather
     * than by the whole file.
     */
    private fun embedPages(doc: PDDocument, source: File) {
        ImageIO.createImageInputStream(source).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: throw AttachmentConversionFailedException(
                    "ImageIO could not decode file despite matching content type — file may be corrupt"
                )
            try {
                //Metadata is not used, and skipping it avoids reading structures we do not need.
                reader.setInput(input, false, true)

                val pageCount = max(1, reader.getNumImages(true))
                if (pageCount > 1) {
                    logger.debug("Image contains {} pages", pageCount)
                }

                for (index in 0 until pageCount) {
                    addImagePage(doc, readCapped(reader, index))
                }
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * Decodes page [index] already downscaled. The subsampling factor is derived from the header
     * dimensions, which are cheap to read, so a huge image is never materialised at full resolution.
     */
    private fun readCapped(reader: ImageReader, index: Int): BufferedImage {
        val width = reader.getWidth(index)
        val height = reader.getHeight(index)
        val factor = ImageUtils.subsamplingFactor(width, height)

        val param = reader.defaultReadParam
        if (factor > 1) {
            logger.debug("Decoding {}x{} image subsampled by a factor of {}", width, height, factor)
            param.setSourceSubsampling(factor, factor, 0, 0)
        }

        return reader.read(index, param)
            ?: throw AttachmentConversionFailedException(
                "ImageIO could not decode file despite matching content type — file may be corrupt"
            )
    }

    private fun addImagePage(doc: PDDocument, image: BufferedImage) {
        val capped = ImageUtils.capResolution(image)
        try {
            drawOnNewPage(doc, LosslessFactory.createFromImage(doc, capped))
        } finally {
            //Release the pixel data as soon as it has been encoded, before decoding the next page.
            capped.flush()
        }
    }

    private fun drawOnNewPage(doc: PDDocument, xImage: PDImageXObject) {
        val page = PDPage(A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { contentStream ->
            contentStream.drawImage(xImage, placementMatrix(xImage.width, xImage.height))
        }
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
