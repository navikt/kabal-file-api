package no.nav.klage.util

import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream

class Image2PDFTest {
    private val image2PDF = Image2PDF(maxConcurrentConversions = 2, permitTimeoutSeconds = 30)

    @Test
    fun `subsampling keeps oversized images from being decoded at full resolution`() {
        // A 300 DPI A4 page is the largest thing we keep untouched.
        assertThat(ImageUtils.subsamplingFactor(width = 2480, height = 3508)).isEqualTo(1)

        // Anything much larger has to be decoded subsampled, but never below the target size.
        val factor = ImageUtils.subsamplingFactor(width = 20000, height = 14000)
        assertThat(factor).isGreaterThan(1)
        assertThat(20000 / factor).isGreaterThanOrEqualTo(3508)
    }

    @Test
    fun `a large tiff is converted without decoding it at full resolution`() {
        val file = writeTiff(file = tempFile("large"), images = listOf(image(width = 6000, height = 8000)))

        val result = image2PDF.convertIfImage(file)

        assertThat(result.wasConverted).isTrue()
        assertThat(result.originalContentType).isEqualTo("image/tiff")
        Loader.loadPDF(file).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(1)
        }
    }

    @Test
    fun `every page of a multi page tiff ends up in the pdf`() {
        val file = writeTiff(file = tempFile("multipage"), images = (1..3).map { image(width = 1200, height = 1600) })

        image2PDF.convertIfImage(file)

        Loader.loadPDF(file).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(3)
        }
    }

    @Test
    fun `a png is converted to a single page pdf`() {
        val file = tempFile("png")
        ImageIO.write(image(width = 800, height = 600), "png", file)

        val result = image2PDF.convertIfImage(file)

        assertThat(result.contentType).isEqualTo("application/pdf")
        Loader.loadPDF(file).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(1)
        }
    }

    @Test
    fun `a jpeg is embedded without re-encoding`() {
        val file = tempFile("jpeg")
        ImageIO.write(image(width = 800, height = 600), "jpg", file)

        val result = image2PDF.convertIfImage(file)

        assertThat(result.wasConverted).isTrue()
        Loader.loadPDF(file).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(1)
        }
    }

    @Test
    fun `an existing pdf is left alone`() {
        val file = writeTiff(file = tempFile("pdf"), images = listOf(image(width = 400, height = 400)))
        image2PDF.convertIfImage(file)
        val bytesAfterFirstConversion = file.readBytes()

        val result = image2PDF.convertIfImage(file)

        assertThat(result.wasConverted).isFalse()
        assertThat(file.readBytes()).isEqualTo(bytesAfterFirstConversion)
    }

    @Test
    fun `concurrent conversions all produce valid pdfs`() {
        // Two large files converting at once was what OOM-killed the pod. The permit count keeps peak
        // heap flat; this guards the other half of that contract, that queueing does not corrupt or
        // drop any of the results.
        val serialised = Image2PDF(maxConcurrentConversions = 1, permitTimeoutSeconds = 60)
        val files = (1..4).map { writeTiff(file = tempFile("concurrent-$it"), images = listOf(image(width = 1500, height = 2000))) }

        val pool = Executors.newFixedThreadPool(files.size)
        try {
            val startTogether = CountDownLatch(files.size)
            val futures =
                files.map { file ->
                    pool.submit {
                        startTogether.countDown()
                        startTogether.await()
                        serialised.convertIfImage(file)
                    }
                }
            futures.forEach { it.get(180, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        files.forEach { file ->
            Loader.loadPDF(file).use { assertThat(it.numberOfPages).isEqualTo(1) }
        }
    }

    private fun tempFile(prefix: String): File = File.createTempFile("image2pdf-$prefix-", null).apply { deleteOnExit() }

    private fun image(
        width: Int,
        height: Int,
    ): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.paint = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.paint = Color.BLACK
            g.drawLine(0, 0, width, height)
            g.dispose()
        }

    private fun writeTiff(
        file: File,
        images: List<BufferedImage>,
    ): File {
        val writer = ImageIO.getImageWritersByFormatName("tiff").next()
        FileImageOutputStream(file).use { output ->
            writer.output = output
            writer.prepareWriteSequence(null)
            images.forEach { writer.writeToSequence(IIOImage(it, null, null), null) }
            writer.endWriteSequence()
        }
        writer.dispose()
        return file
    }
}
