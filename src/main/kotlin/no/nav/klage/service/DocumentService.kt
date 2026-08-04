package no.nav.klage.service

import com.google.cloud.storage.*
import io.micrometer.core.instrument.MeterRegistry
import no.nav.klage.clients.clamav.ClamAvClient
import no.nav.klage.config.AsyncConfiguration.Companion.DOCUMENT_DELETE_EXECUTOR
import no.nav.klage.getLogger
import no.nav.klage.util.Image2PDF
import no.nav.klage.util.measureDuration
import no.nav.klage.util.recordDistribution
import no.nav.klage.util.recordTimer
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@Service
class DocumentService(
    private val gcsStorage: Storage,
    private val clamAvClient: ClamAvClient,
    private val image2PDF: Image2PDF,
    private val meterRegistry: MeterRegistry,
    @Qualifier(DOCUMENT_DELETE_EXECUTOR)
    private val documentDeleteExecutor: Executor,
    @Value($$"${bucket}")
    private val bucket: String,
) {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)

        private const val VIRUSCHECK_DURATION_TIMER = "kabalfileapi.document.viruscheck.duration"
        private const val CONVERSION_DURATION_TIMER = "kabalfileapi.document.conversion.duration"
        private const val DELETE_DURATION_TIMER = "kabalfileapi.document.delete.duration"
        private const val DELETE_FAILED_COUNTER = "kabalfileapi.document.delete.failed"
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

    /**
     * Fire and forget: the caller gets a response as soon as the deletion is queued. Deleting the
     * object in GCS takes a few hundred milliseconds, and no client has anything to do with the
     * outcome, so it is done on a background thread. Failures are logged and counted.
     */
    fun deleteDocument(id: String) {
        logger.debug("Queueing deletion of document with id {}", id)
        documentDeleteExecutor.execute {
            deleteDocumentInGCS(id)
        }
    }

    private fun deleteDocumentInGCS(id: String) {
        try {
            val (deleted, duration) = measureDuration {
                gcsStorage.delete(BlobId.of(bucket, id.toPath()))
            }
            if (deleted) {
                logger.debug("Document {} was deleted in {} ms.", id, duration.toMillis())
            } else {
                logger.debug("Document {} not found, nothing to delete.", id)
            }
            meterRegistry.recordTimer(
                DELETE_DURATION_TIMER,
                duration,
                "outcome", if (deleted) "deleted" else "not_found",
            )
        } catch (e: Exception) {
            logger.error("Could not delete document $id in GCS.", e)
            meterRegistry.counter(DELETE_FAILED_COUNTER).increment()
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

    fun scanDocument(id: String): ScanResult {
        logger.debug("Scanning document with id {}", id)

        val blob = getBlobOrThrow(id)
        val declaredContentType = blob.contentType ?: "unknown"

        val tempFile = Files.createTempFile("scan-", null)
        try {
            blob.downloadTo(tempFile)

            //Scan the original bytes the user actually uploaded.
            val (hasVirus, virusCheckDuration) = measureDuration {
                clamAvClient.hasVirus(tempFile.toFile())
            }
            meterRegistry.recordTimer(
                VIRUSCHECK_DURATION_TIMER,
                virusCheckDuration,
                "fileType", declaredContentType,
                "outcome", if (hasVirus) "virus" else "clean",
            )

            if (hasVirus) {
                return ScanResult(
                    hasVirus = true,
                    size = blob.size,
                    contentType = declaredContentType,
                    requiresConversion = false,
                    generation = blob.generation,
                )
            }

            //Rejects unsupported types here, so the client knows before it announces a conversion step.
            val fileTypeInfo = image2PDF.inspect(tempFile.toFile())

            return ScanResult(
                hasVirus = false,
                size = blob.size,
                contentType = fileTypeInfo.detectedContentType,
                requiresConversion = fileTypeInfo.requiresConversion,
                generation = blob.generation,
            )
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    /**
     * [scannedGeneration] is the generation returned by [scanDocument]. The upload policy stays valid
     * for a while, so the object could have been replaced after it was scanned; converting a
     * different generation than the one we scanned would let unscanned bytes through.
     */
    fun convertDocument(id: String, scannedGeneration: Long): ConvertResult {
        logger.debug("Converting document with id {}", id)

        val blob = getBlobOrThrow(id)

        if (blob.generation != scannedGeneration) {
            logger.warn("Document {} was replaced after it was scanned, refusing to convert it.", id)
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Document was replaced after it was scanned. Please upload it again.",
            )
        }

        //Declared content type from upload; refined to the actually detected type once we convert.
        var fileType = blob.contentType ?: "unknown"

        val tempFile = Files.createTempFile("convert-", null)
        try {
            blob.downloadTo(tempFile)

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
                meterRegistry.recordDistribution(
                    SIZE_SUMMARY,
                    blob.size.toDouble(),
                    "bytes",
                    "fileType", fileType,
                    "outcome", "passthrough",
                )
                return ConvertResult(
                    size = blob.size,
                    contentType = conversion.contentType,
                    wasConverted = false,
                )
            }

            //Overwrite the same object with the generated PDF, streamed from disk. The generation
            //precondition makes the write fail instead of clobbering the object if it was deleted or
            //re-uploaded while we were converting.
            val pdfBlobInfo = BlobInfo.newBuilder(BlobId.of(bucket, id.toPath()))
                .setContentType(conversion.contentType)
                .build()
            try {
                gcsStorage.createFrom(
                    pdfBlobInfo,
                    tempFile,
                    Storage.BlobWriteOption.generationMatch(blob.generation),
                )
            } catch (e: StorageException) {
                if (e.code == HttpStatus.PRECONDITION_FAILED.value()) {
                    logger.warn("Document {} was modified while being converted, conversion discarded.", id)
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Document was modified while being converted. Please try again.",
                        e,
                    )
                }
                throw e
            }

            val convertedSize = Files.size(tempFile)
            meterRegistry.recordDistribution(
                SIZE_SUMMARY,
                convertedSize.toDouble(),
                "bytes",
                "fileType", fileType,
                "outcome", "converted",
            )

            return ConvertResult(
                size = convertedSize,
                contentType = conversion.contentType,
                wasConverted = true,
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

data class ScanResult(
    val hasVirus: Boolean,
    val size: Long?,
    val contentType: String?,
    val requiresConversion: Boolean,
    val generation: Long,
)

data class ConvertResult(
    val size: Long?,
    val contentType: String?,
    val wasConverted: Boolean,
)
