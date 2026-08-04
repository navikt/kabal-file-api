package no.nav.klage.controller


import jakarta.servlet.http.HttpServletResponse
import no.nav.klage.getLogger
import no.nav.klage.service.*
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@ProtectedWithClaims(issuer = "azuread")
@RequestMapping("document")
class KabalController(private val documentService: DocumentService) {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)
    }

    @GetMapping("{id}")
    fun getDocument(
        @PathVariable("id") id: String,
        response: HttpServletResponse,
    ) {
        logger.debug("Get document requested with id {}", id)

        response.addHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=file.pdf")
        response.contentType = MediaType.APPLICATION_PDF_VALUE

        documentService.getDocumentAsBlob(id).downloadTo(response.outputStream)
    }

    @GetMapping("{id}/signedurl")
    fun getDocumentAsSignedURL(
        @PathVariable("id") id: String,
    ): String {
        logger.debug("getDocumentAsSignedURL requested with id {}", id)

        return documentService.getDocumentAsSignedUrl(id = id)
    }

    @PostMapping("{id}/signedurl")
    fun getDocumentAsSignedURLAndHeaders(
        @PathVariable("id") id: String,
        @RequestBody request: SignedUrlRequest,
    ): String {
        logger.debug("getDocumentAsSignedURLAndHeaders requested with id {}", id)

        return documentService.getDocumentAsSignedUrl(id = id, headers = request.headers)
    }

    @PostMapping
    fun addDocument(
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<DocumentCreatedResponse> {
        logger.debug("Add document requested.")
        val result = documentService.saveDocument(file)
        return ResponseEntity(DocumentCreatedResponse(result), HttpStatus.CREATED)
    }

    @PostMapping("uploadpolicy")
    fun createUploadPolicy(@RequestBody request: UploadUrlRequest): UploadPostPolicy {
        logger.debug("Create upload policy requested for content type {}", request.contentType)
        return documentService.createUploadPostPolicy(request.contentType)
    }

    @GetMapping("{id}/metadata")
    fun getDocumentMetadata(@PathVariable("id") id: String): DocumentMetadata {
        logger.debug("Get document metadata requested with id {}", id)
        return documentService.getDocumentMetadata(id)
    }

    @PostMapping("{id}/scan")
    fun scanDocument(@PathVariable("id") id: String): ScanResult {
        logger.debug("Scan document requested with id {}", id)
        return documentService.scanDocument(id)
    }

    @PostMapping("{id}/convert")
    fun convertDocument(
        @PathVariable("id") id: String,
        @RequestBody request: ConvertRequest,
    ): ConvertResult {
        logger.debug("Convert document requested with id {}", id)
        return documentService.convertDocument(id = id, scannedGeneration = request.scannedGeneration)
    }

    @DeleteMapping("{id}")
    fun deleteDocument(@PathVariable("id") id: String): Boolean {
        logger.debug("Delete document requested.")
        return documentService.deleteDocument(id)
    }

    data class DocumentCreatedResponse(val id: String)

    data class SignedUrlRequest(val headers: Map<String, String> = emptyMap())

    data class UploadUrlRequest(val contentType: String)

    data class ConvertRequest(val scannedGeneration: Long)
}