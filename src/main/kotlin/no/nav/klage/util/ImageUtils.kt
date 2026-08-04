package no.nav.klage.util

import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
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
     * Returns the image unchanged unless it exceeds [MAX_DPI] when rendered on a full A4 page, in
     * which case it is downscaled (preserving aspect ratio) using high quality interpolation.
     *
     * The image is never resampled down to the size of an A4 page in points; fitting to the page is
     * done through the PDF placement matrix, so the full pixel data is preserved.
     */
    fun capResolution(image: BufferedImage): BufferedImage {
        //Compare against the A4 bounds in the same orientation as the image, so a landscape image is
        //allowed just as many pixels as its rotated portrait equivalent.
        val boundLong = max(maxPixelWidth, maxPixelHeight)
        val boundShort = min(maxPixelWidth, maxPixelHeight)
        val boundWidth = if (image.width >= image.height) boundLong else boundShort
        val boundHeight = if (image.width >= image.height) boundShort else boundLong

        val scale = min(
            boundWidth.toDouble() / image.width,
            boundHeight.toDouble() / image.height,
        )
        if (scale >= 1.0) {
            return image
        }

        return resize(
            image = image,
            newWidth = max(1, (image.width * scale).roundToInt()),
            newHeight = max(1, (image.height * scale).roundToInt()),
        )
    }

    private fun resize(image: BufferedImage, newWidth: Int, newHeight: Int): BufferedImage {
        val target = BufferedImage(
            newWidth,
            newHeight,
            if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        )
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(image, 0, 0, newWidth, newHeight, null)
        } finally {
            g.dispose()
        }
        return target
    }
}
