package no.nav.klage.util

import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageUtils {
    /**
     * Upper bound on the resolution we keep when embedding an image in an A4 page. Images with a
     * higher effective resolution are downscaled; anything at or below this is embedded untouched.
     */
    const val MAX_DPI = 300

    private const val PDF_USER_SPACE_DPI = 72f

    private val maxPixelWidth = (PDRectangle.A4.width / PDF_USER_SPACE_DPI * MAX_DPI).roundToInt()
    private val maxPixelHeight = (PDRectangle.A4.height / PDF_USER_SPACE_DPI * MAX_DPI).roundToInt()

    /**
     * The pixel bounds an image of the given orientation is allowed to occupy, so a landscape image is
     * allowed just as many pixels as its rotated portrait equivalent.
     */
    private fun boundsFor(
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val boundLong = max(maxPixelWidth, maxPixelHeight)
        val boundShort = min(maxPixelWidth, maxPixelHeight)
        return if (width >= height) boundLong to boundShort else boundShort to boundLong
    }

    /**
     * How much a [width] x [height] image has to shrink to fit within the [MAX_DPI] bounds. Values at
     * or below 1 mean the image is already small enough.
     */
    private fun shrinkFactor(
        width: Int,
        height: Int,
    ): Double {
        val (boundWidth, boundHeight) = boundsFor(width, height)
        return max(width.toDouble() / boundWidth, height.toDouble() / boundHeight)
    }

    /**
     * The integer subsampling factor to hand to [javax.imageio.ImageReadParam.setSourceSubsampling] so
     * an oversized image never has to be fully decoded into heap. Decoding at this factor lands within
     * a factor of two of the target size; [capResolution] then does the remaining fractional scaling
     * with proper interpolation.
     *
     * This is the difference between holding a 20000x14000 scan in memory (over 1 GB as ARGB) and
     * holding something A4 sized, and is what keeps conversion of large TIFFs inside the memory limit.
     */
    fun subsamplingFactor(
        width: Int,
        height: Int,
    ): Int {
        val shrink = shrinkFactor(width, height)
        if (shrink <= 1.0) {
            return 1
        }
        return max(1, floor(shrink).toInt())
    }

    /**
     * Returns the image unchanged unless it exceeds [MAX_DPI] when rendered on a full A4 page, in
     * which case it is downscaled (preserving aspect ratio) using high quality interpolation.
     *
     * The image is never resampled down to the size of an A4 page in points; fitting to the page is
     * done through the PDF placement matrix, so the full pixel data is preserved.
     */
    fun capResolution(image: BufferedImage): BufferedImage {
        val shrink = shrinkFactor(image.width, image.height)
        if (shrink <= 1.0) {
            return image
        }

        val scale = 1.0 / shrink
        return resize(
            image = image,
            newWidth = max(1, (image.width * scale).roundToInt()),
            newHeight = max(1, (image.height * scale).roundToInt()),
        )
    }

    /**
     * Keeps grayscale and bilevel images in a byte per pixel representation instead of promoting them
     * to 32 bit RGB. Scanned documents are frequently bilevel, and promoting them would quadruple both
     * the heap used while scaling and the size of the embedded image.
     */
    private fun targetTypeFor(image: BufferedImage): Int =
        when {
            image.colorModel.hasAlpha() -> BufferedImage.TYPE_INT_ARGB

            image.type == BufferedImage.TYPE_BYTE_GRAY ||
                image.type == BufferedImage.TYPE_USHORT_GRAY ||
                image.type == BufferedImage.TYPE_BYTE_BINARY -> BufferedImage.TYPE_BYTE_GRAY

            else -> BufferedImage.TYPE_INT_RGB
        }

    private fun resize(
        image: BufferedImage,
        newWidth: Int,
        newHeight: Int,
    ): BufferedImage {
        val target = BufferedImage(newWidth, newHeight, targetTypeFor(image))
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(image, 0, 0, newWidth, newHeight, null)
        } finally {
            g.dispose()
        }
        // The source is not needed once drawn into the smaller target; releasing it here keeps the peak
        // at one large image rather than two.
        image.flush()
        return target
    }
}
