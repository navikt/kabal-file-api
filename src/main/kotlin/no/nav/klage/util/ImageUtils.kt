package no.nav.klage.util

import no.nav.klage.getLogger
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Image
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage

object ImageUtils {

    private val logger = getLogger(javaClass)

    /**
     * Rotates landscape images to portrait and scales the image down so it fits within an A4 page,
     * preserving aspect ratio. Images already within A4 bounds are returned unchanged.
     */
    fun scaleToA4(image: BufferedImage): BufferedImage {
        val A4 = PDRectangle.A4
        val rotated = rotatePortrait(image)
        val pdfPageDim = Dimension(A4.width.toInt(), A4.height.toInt())
        val origDim = Dimension(rotated.width, rotated.height)
        val newDim = getScaledDimension(origDim, pdfPageDim)
        return if (newDim == origDim) {
            rotated
        } else {
            scaleDown(rotated, newDim)
        }
    }

    private fun rotatePortrait(image: BufferedImage): BufferedImage {
        if (image.height >= image.width) {
            return image
        }
        if (image.type == BufferedImage.TYPE_CUSTOM) {
            logger.warn("Cannot not rotate image with unknown type.")
            return image
        }
        var rotatedImage = BufferedImage(image.height, image.width, image.type)
        val transform = AffineTransform()
        transform.rotate(
            Math.toRadians(90.0),
            image.height / 2f.toDouble(),
            image.height / 2f.toDouble()
        )
        val op = AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR)
        rotatedImage = op.filter(image, rotatedImage)
        return rotatedImage
    }

    private fun getScaledDimension(imgSize: Dimension, a4: Dimension): Dimension {
        val originalWidth = imgSize.width
        val originalHeight = imgSize.height
        val a4Width = a4.width
        val a4Height = a4.height
        var newWidth = originalWidth
        var newHeight = originalHeight
        if (originalWidth > a4Width) {
            newWidth = a4Width
            newHeight = newWidth * originalHeight / originalWidth
        }
        if (newHeight > a4Height) {
            newHeight = a4Height
            newWidth = newHeight * originalWidth / originalHeight
        }
        return Dimension(newWidth, newHeight)
    }

    private fun scaleDown(origImage: BufferedImage, newDim: Dimension): BufferedImage {
        val newWidth = newDim.getWidth().toInt()
        val newHeight = newDim.getHeight().toInt()
        val tempImg = origImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH)
        val scaledImg = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_3BYTE_BGR)
        val g = scaledImg.graphics as Graphics2D
        g.drawImage(tempImg, 0, 0, null)
        g.dispose()
        return scaledImg
    }
}
