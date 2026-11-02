package no.nav.klage.config.security

import no.nav.klage.auth.test.AzureSecurityContractTest
import no.nav.klage.controller.KabalController
import no.nav.klage.service.DocumentService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(KabalController::class)
@Import(SecurityConfiguration::class)
class SecurityConfigurationTest : AzureSecurityContractTest() {
    @MockitoBean
    private lateinit var documentService: DocumentService

    override fun protectedRequest() = delete("/document/abc")

    override fun assertAccepted(result: ResultActions) {
        result.andExpect(status().isAccepted)
        verify(documentService).deleteDocument("abc")
    }

    override fun unauthenticatedPaths() = listOf("/internal/health", "/internal/info", "/internal/prometheus")

    @Test
    fun `other internal endpoints require token`() {
        mockMvc.perform(get("/internal/metrics")).andExpect(status().isUnauthorized)
    }
}
