package no.nav.klage.config

import no.nav.klage.auth.currentAzureToken
import no.nav.klage.auth.navIdent
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.web.context.WebApplicationContext

/**
 * Unlike kabal-document, which has to dig the ident out of the request body, kabal-file-api is called
 * with an on-behalf-of token, so the ident is taken straight from the NAVident claim.
 */
@Configuration
@Profile("dev")
class UnleashConfiguration(
    @Value($$"${NAIS_POD_NAME:local}")
    private val naisPodName: String,
    @Value($$"${NAIS_APP_NAME:kabal-file-api}")
    private val naisAppName: String,
) {
    @Bean
    @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
    fun klageUnleashProxyContext(): KlageUnleashProxyContext =
        KlageUnleashProxyContext(
            // Null for machine-to-machine (client credentials) calls, which have no NAVident claim.
            navIdent = currentAzureToken()?.navIdent,
            appName = naisAppName,
            podName = naisPodName,
        )
}

open class KlageUnleashProxyContext(
    open val navIdent: String?,
    open val appName: String,
    open val podName: String,
)
