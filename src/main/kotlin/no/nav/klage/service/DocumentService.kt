package no.nav.klage.service

import com.google.cloud.storage.*
import io.micrometer.core.instrument.MeterRegistry
import no.nav.klage.clients.clamav.ClamAvClient
import no.nav.klage.getLogger
import no.nav.klage.util.Image2PDF
import no.nav.klage.util.measureDuration
import no.nav.klage.util.recordDistribution
import no.nav.klage.util.recordTimer
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.*
import java.util.concurrent.TimeUnit

@Service
class DocumentService(
    private val gcsStorage: Storage,
    private val clamAvClient: ClamAvClient,
    private val image2PDF: Image2PDF,
    private val meterRegistry: MeterRegistry,
    @Value($$"${bucket}")
    private val bucket: String,
) {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)

        private const val PROCESS_DURATION_TIMER = "kabalfileapi.document.process.duration"
        private const val VIRUSCHECK_DURATION_TIMER = "kabalfileapi.document.viruscheck.duration"
        private const val CONVERSION_DURATION_TIMER = "kabalfileapi.document.conversion.duration"
        private const val SIZE_SUMMARY = "kabalfileapi.document.size.bytes"

        //Enforced by GCS via the upload policy. Must not exceed Int.MAX_VALUE, since the
        //content-length-range condition is expressed in ints. 512 MB.
        private const val MAX_UPLOAD_SIZE = 536870912

        //The client uploads the raw file directly to GCS, so we only allow types we can turn into a PDF.
        private val ALLOWED_UPLOAD_CONTENT_TYPES = setOf(
            MediaType.APPLICATION_PDF_VALUE,
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/tiff",
            "image/heic",
            "image/heif",
        )
    }

    fun getDocumentAsBlob(id: String): Blob {
        logger.debug("Getting document with id {}", id)

        return getBlobOrThrow(id)
    }

    fun getDocumentAsSignedUrl(id: String, headers: Map<String, String> = emptyMap()): String {
        logger.debug("Getting document as signed URL with id {}", id)

        val blob = getBlobOrThrow(id)

        val queryParams = headers.map { (key, value) -> "response-$key" to value }.toMap()

        return blob.signUrl(
            1, TimeUnit.MINUTES,
            Storage.SignUrlOption.withQueryParams(queryParams)
        ).toExternalForm()
    }

    fun deleteDocument(id: String): Boolean {
        logger.debug("Deleting document with id {}", id)
        return gcsStorage.delete(BlobId.of(bucket, id.toPath())).also {
            if (it) {
                logger.debug("Document was deleted.")
            } else {
                logger.debug("Document not found, nothing to delete.")
            }
        }
    }

    fun saveDocument(file: MultipartFile): String {
        logger.debug("Saving document")

        val id = UUID.randomUUID().toString()

        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, id.toPath()))
            .setContentType(file.contentType)
            .build()

        //Staged on disk so the upload can be retried; GCS cannot retry a one-shot InputStream upload.
        val tempFile = Files.createTempFile("upload-", null)
        try {
            file.transferTo(tempFile)
            gcsStorage.createFrom(blobInfo, tempFile)
        } finally {
            Files.deleteIfExists(tempFile)
        }

        logger.debug("Document saved, and id is {}", id)

        return id
    }

    /**
     * The client must POST multipart/form-data to [UploadPostPolicy.url] with every entry of
     * [UploadPostPolicy.fields] as a form field, and the file itself as the last field ("file").
     */
    fun createUploadPostPolicy(contentType: String): UploadPostPolicy {
        if (contentType !in ALLOWED_UPLOAD_CONTENT_TYPES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported content type: $contentType")
        }

        val id = UUID.randomUUID().toString()

        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, id.toPath())).build()

        val fields = PostPolicyV4.PostFieldsV4.newBuilder()
            .setContentType(contentType)
            .build()

        val conditions = PostPolicyV4.PostConditionsV4.newBuilder()
            .addContentLengthRangeCondition(1, MAX_UPLOAD_SIZE)
            .build()

        val policy = gcsStorage.generateSignedPostPolicyV4(
            blobInfo,
            30, TimeUnit.MINUTES,
            fields,
            conditions,
        )

        logger.debug("Created signed upload policy for new document with id {}", id)

        return UploadPostPolicy(
            id = id,
            url = policy.url,
            fields = policy.fields,
            contentType = contentType,
            maxSize = MAX_UPLOAD_SIZE.toLong(),
        )
    }

    fun getDocumentMetadata(id: String): DocumentMetadata {
        val blob = getBlob(id)
        return if (blob == null) {
            DocumentMetadata(exists = false, size = null, contentType = null)
        } else {
            DocumentMetadata(exists = true, size = blob.size, contentType = blob.contentType)
        }
    }

    fun processDocument(id: String): ProcessResult {
        logger.debug("Processing (scan + convert) document with id {}", id)

        val (result, processDuration) = measureDuration {
            processDocumentInternal(id)
        }

        meterRegistry.recordTimer(
            PROCESS_DURATION_TIMER,
            processDuration,
            "fileType", result.fileType,
            "outcome", result.outcome,
        )
        meterRegistry.recordDistribution(
            SIZE_SUMMARY,
            (result.processResult.size ?: 0L).toDouble(),
            "bytes",
            "fileType", result.fileType,
            "outcome", result.outcome,
        )

        return result.processResult
    }

    private data class ProcessInternalResult(
        val processResult: ProcessResult,
        val fileType: String,
        val outcome: String,
    )

    private fun processDocumentInternal(id: String): ProcessInternalResult {
        val blob = getBlobOrThrow(id)

        //Declared content type from upload; refined to the actually detected type once we convert.
        var fileType = blob.contentType ?: "unknown"

        val tempFile = Files.createTempFile("process-", null)
        try {
            blob.downloadTo(tempFile)

            //Scan the original bytes the user actually uploaded.
            val (hasVirus, virusCheckDuration) = measureDuration {
                clamAvClient.hasVirus(tempFile.toFile())
            }
            meterRegistry.recordTimer(VIRUSCHECK_DURATION_TIMER, virusCheckDuration, "fileType", fileType)

            if (hasVirus) {
                return ProcessInternalResult(
                    processResult = ProcessResult(
                        hasVirus = true,
                        size = blob.size,
                        contentType = blob.contentType,
                        wasConverted = false,
                    ),
                    fileType = fileType,
                    outcome = "virus",
                )
            }

            val (conversion, conversionDuration) = measureDuration {
                image2PDF.convertIfImage(tempFile.toFile())
            }
            fileType = conversion.originalContentType
            meterRegistry.recordTimer(
                CONVERSION_DURATION_TIMER,
                conversionDuration,
                "fileType", fileType,
                "converted", conversion.wasConverted.toString(),
            )

            if (!conversion.wasConverted) {
                return ProcessInternalResult(
                    processResult = ProcessResult(
                        hasVirus = false,
                        size = blob.size,
                        contentType = conversion.contentType,
                        wasConverted = false,
                    ),
                    fileType = fileType,
                    outcome = "passthrough",
                )
            }

            //Overwrite the same object with the generated PDF, streamed from disk.
            val pdfBlobInfo = BlobInfo.newBuilder(BlobId.of(bucket, id.toPath()))
                .setContentType(conversion.contentType)
                .build()
            gcsStorage.createFrom(pdfBlobInfo, tempFile)

            return ProcessInternalResult(
                processResult = ProcessResult(
                    hasVirus = false,
                    size = Files.size(tempFile),
                    contentType = conversion.contentType,
                    wasConverted = true,
                ),
                fileType = fileType,
                outcome = "converted",
            )
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun getBlob(id: String): Blob? = gcsStorage.get(bucket, id.toPath())

    private fun getBlobOrThrow(id: String): Blob =
        getBlob(id) ?: run {
            logger.warn("Document not found: {}", id)
            throw FileNotFoundException()
        }

    private fun String.toPath() = "document/$this"
}

data class UploadPostPolicy(
    val id: String,
    val url: String,
    val fields: Map<String, String>,
    val contentType: String,
    val maxSize: Long,
)

data class DocumentMetadata(
    val exists: Boolean,
    val size: Long?,
    val contentType: String?,
)

data class ProcessResult(
    val hasVirus: Boolean,
    val size: Long?,
    val contentType: String?,
    val wasConverted: Boolean,
)
