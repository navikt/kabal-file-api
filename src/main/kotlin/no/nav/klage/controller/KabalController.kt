package no.nav.klage.controller


import jakarta.servlet.http.HttpServletResponse
import no.nav.klage.getLogger
import no.nav.klage.service.ConvertResult
import no.nav.klage.service.DocumentMetadata
import no.nav.klage.service.DocumentService
import no.nav.klage.service.ScanResult
import no.nav.klage.service.UploadPostPolicy
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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

    @PostMapping("uploadpolicies")
    fun createUploadPolicies(@RequestBody request: UploadUrlsRequest): List<UploadPostPolicy> {
        logger.debug("Create upload policies requested for content types {}", request.contentTypes)
        return documentService.createUploadPostPolicies(request.contentTypes)
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
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun deleteDocument(@PathVariable("id") id: String) {
        logger.debug("Delete document requested.")
        documentService.deleteDocument(id)
    }

    data class DocumentCreatedResponse(val id: String)

    data class SignedUrlRequest(val headers: Map<String, String> = emptyMap())

    data class UploadUrlsRequest(val contentTypes: List<String>)

    data class ConvertRequest(val scannedGeneration: Long)
}