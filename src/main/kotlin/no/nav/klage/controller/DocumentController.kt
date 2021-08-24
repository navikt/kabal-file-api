package no.nav.klage.controller


import no.nav.klage.getLogger
import no.nav.klage.service.DocumentService
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.io.FileNotFoundException

@RestController
@ProtectedWithClaims(issuer = "azuread")
@RequestMapping("document")
class KabalController(private val documentService: DocumentService) {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)
    }

    @GetMapping("{id}")
    fun getDocument(@PathVariable("id") id: String): ResponseEntity<Resource> {
        logger.debug("Get document requested with id {}", id)
        return try {
            val resource = documentService.getDocumentAsResource(id)
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource)
        } catch (fnfe: FileNotFoundException) {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping
    fun addDocument(
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<DocumentCreatedResponse> {
        logger.debug("Add document requested.")
        val result = documentService.saveDocument(file)
        return ResponseEntity(DocumentCreatedResponse(result), HttpStatus.CREATED)
    }

    @DeleteMapping("{id}")
    fun deleteDocument(@PathVariable("id") id: String): Boolean {
        logger.debug("Delete document requested.")
        return documentService.deleteDocument(id)
    }

    data class DocumentCreatedResponse(val id: String)
}