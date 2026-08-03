package no.nav.klage.clients.clamav

import no.nav.klage.getLogger
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.FileSystemResource
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.io.File

@Component
class ClamAvClient(
    @Qualifier("clamAvWebClient") private val clamAvWebClient: WebClient,
) {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)
    }

    fun hasVirus(file: File): Boolean {
        logger.debug("Scanning document for virus")

        val fileSizeMb = file.length() / (1024.0 * 1024.0)

        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part(file.name, FileSystemResource(file)).filename(file.name)

        val start = System.currentTimeMillis()
        val response = clamAvWebClient.post()
            .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
            .retrieve()
            .bodyToMono<List<ScanResult>>()
            .block()?.firstOrNull() ?: throw RuntimeException("Received empty response from ClamAV")

        val durationMs = System.currentTimeMillis() - start
        logger.debug(
            "ClamAV scan completed in {} ms for file of {} MB. Result: {}",
            durationMs,
            String.format("%.2f", fileSizeMb),
            response.result
        )

        return when (response.result) {
            ClamAvResult.OK -> false
            ClamAvResult.FOUND -> {
                logger.warn("Virus found in file: {}. Virus: {}", response.filename, response.virus)
                true
            }

            ClamAvResult.ERROR -> {
                logger.error("Error scanning file for virus: {}. Error: {}", response.filename, response.error)
                throw RuntimeException("Error from ClamAV virus scan on file: ${response.filename}. Error: ${response.error}")
            }
        }
    }
}
