package no.nav.klage.clients.klageunleashproxy

import no.nav.klage.config.KlageUnleashProxyContext
import no.nav.klage.getLogger
import org.springframework.context.annotation.Profile
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Only available in dev. The toggles this client is used for simulate failures, and must never be
 * reachable in prod.
 */
@Component
@Profile("dev")
class KlageUnleashProxyClient(
    private val klageUnleashProxyContext: KlageUnleashProxyContext,
    private val klageUnleashProxyWebClient: WebClient,
) {
    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)
    }

    @Retryable
    fun isEnabled(feature: String): Boolean {
        if (klageUnleashProxyContext.navIdent == null) {
            logger.debug("Cannot check feature toggle '{}' without navIdent. Returning false.", feature)
            return false
        }

        val requestBody = UnleashProxyRequest(
            navIdent = klageUnleashProxyContext.navIdent!!,
            appName = klageUnleashProxyContext.appName,
            podName = klageUnleashProxyContext.podName,
        )

        return klageUnleashProxyWebClient.post()
            .uri("/features/${feature}")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono<FeatureToggleResponse>()
            .block()?.enabled ?: false
    }
}
