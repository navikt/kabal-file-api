package no.nav.klage.service

import no.nav.klage.clients.klageunleashproxy.KlageUnleashProxyClient
import no.nav.klage.getLogger
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * Lets us provoke virus scan and conversion errors from Unleash, so the clients can be tested against
 * failure responses without having to produce actual errors
 */
interface FailureSimulationService {
    fun shouldFailVirusScan(): Boolean
    fun shouldFailConversion(): Boolean
}

/**
 * Active in every environment except dev. The toggles are a test aid, and are hardwired off here so
 * an Unleash misconfiguration cannot break document handling in prod.
 */
@Service
@Profile("!dev")
class NoFailureSimulationService : FailureSimulationService {
    override fun shouldFailVirusScan(): Boolean = false
    override fun shouldFailConversion(): Boolean = false
}

@Service
@Profile("dev")
class UnleashFailureSimulationService(
    private val klageUnleashProxyClient: KlageUnleashProxyClient,
) : FailureSimulationService {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)

        const val FAIL_VIRUS_SCAN = "fail-virus-scan"
        const val FAIL_CONVERSION = "fail-conversion"
    }

    override fun shouldFailVirusScan(): Boolean = isEnabled(FAIL_VIRUS_SCAN)

    override fun shouldFailConversion(): Boolean = isEnabled(FAIL_CONVERSION)

    /**
     * Never lets an Unleash problem become a document problem: if the toggle cannot be read, the
     * simulation is simply off.
     */
    private fun isEnabled(feature: String): Boolean =
        try {
            klageUnleashProxyClient.isEnabled(feature).also { enabled ->
                if (enabled) {
                    logger.warn("Feature toggle '{}' is enabled. Simulating failure.", feature)
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not read feature toggle '$feature'. Continuing without simulated failure.", e)
            false
        }
}
