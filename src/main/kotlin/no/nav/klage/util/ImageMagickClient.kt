package no.nav.klage.util

import no.nav.klage.exceptions.AttachmentCouldNotBeConvertedException
import no.nav.klage.getLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Converts HEIC/HEIF images to PNG by shelling out to ImageMagick (which must be built with the
 * libheif delegate). HEIC cannot be decoded in pure Java, so this native step is required.
 */
@Component
class ImageMagickClient(
    @Value($$"${IMAGE_MAGICK_COMMAND:convert}")
    private val imageMagickCommand: String,
    @Value($$"${IMAGE_MAGICK_TIMEOUT_SECONDS:120}")
    private val timeoutSeconds: Long,
) {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)
    }

    /**
     * Converts the given HEIC/HEIF file to a new temporary PNG file. Caller is responsible for
     * deleting the returned file.
     */
    fun convertHeicToPng(input: File): File {
        val output = Files.createTempFile("heic-", ".png").toFile()

        //"[0]" selects the first image in the container, so multi-image HEICs yield a single output.
        val command = listOf(
            imageMagickCommand,
            "${input.absolutePath}[0]",
            output.absolutePath,
        )

        logger.debug("Converting HEIC/HEIF to PNG using: {}", command.joinToString(" "))

        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            output.delete()
            logger.error("Could not start ImageMagick ('$imageMagickCommand'). Is it installed?", e)
            throw AttachmentCouldNotBeConvertedException()
        }

        val finishedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finishedInTime) {
            process.destroyForcibly()
            output.delete()
            logger.error("ImageMagick timed out after {} s while converting HEIC/HEIF", timeoutSeconds)
            throw AttachmentCouldNotBeConvertedException()
        }

        if (process.exitValue() != 0 || !output.exists() || output.length() == 0L) {
            val processOutput = process.inputStream.bufferedReader().readText()
            output.delete()
            logger.error("ImageMagick failed to convert HEIC/HEIF (exit {}): {}", process.exitValue(), processOutput)
            throw AttachmentCouldNotBeConvertedException()
        }

        return output
    }
}
