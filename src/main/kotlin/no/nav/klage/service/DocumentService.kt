package no.nav.klage.service

import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import no.nav.klage.getLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.FileNotFoundException
import java.util.*
import java.util.concurrent.TimeUnit

@Service
class DocumentService {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)
    }

    @Value("\${GCS_BUCKET}")
    private lateinit var bucket: String

    fun getDocumentAsBlob(id: String): Blob {
        logger.debug("Getting document with id {}", id)

        val blob = getGcsStorage().get(bucket, id.toPath())

        if (blob == null || !blob.exists()) {
            logger.warn("Document not found: {}", id)
            throw FileNotFoundException()
        }

        return blob
    }

    fun getDocumentAsSignedUrl(id: String): String {
        logger.debug("Getting document as signed URL with id {}", id)

        val blob = getGcsStorage().get(bucket, id.toPath())

        if (blob == null || !blob.exists()) {
            logger.warn("Document not found: {}", id)
            throw FileNotFoundException()
        }

        return blob.signUrl(1, TimeUnit.MINUTES).toExternalForm()
    }

    fun deleteDocument(id: String): Boolean {
        logger.debug("Deleting document with id {}", id)
        return getGcsStorage().delete(BlobId.of(bucket, id.toPath())).also {
            if (it) {
                logger.debug("Document was deleted.")
            } else {
                logger.debug("Document was not found and could not be deleted.")
            }
        }
    }

    fun saveDocument(file: MultipartFile): String {
        logger.debug("Saving document")

        val id = UUID.randomUUID().toString()

        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, id.toPath())).build()
        getGcsStorage().create(blobInfo, file.inputStream).exists()

        logger.debug("Document saved, and id is {}", id)

        return id
    }

    private fun String.toPath() = "document/$this"

    private fun getGcsStorage() = StorageOptions.getDefaultInstance().service
}