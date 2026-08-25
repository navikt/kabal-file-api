package no.nav.klage.config

import no.nav.klage.getLogger
import no.nav.security.token.support.core.context.TokenValidationContextHolder
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

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)

        private const val AZURE_AD_ISSUER = "azuread"
        private const val NAV_IDENT_CLAIM = "NAVident"
    }

    @Bean
    @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
    fun klageUnleashProxyContext(tokenValidationContextHolder: TokenValidationContextHolder): KlageUnleashProxyContext {
        return KlageUnleashProxyContext(
            navIdent = getNavIdent(tokenValidationContextHolder),
            appName = naisAppName,
            podName = naisPodName,
        )
    }

    /**
     * Returns null for machine-to-machine (client credentials) calls, which have no NAVident claim.
     */
    private fun getNavIdent(tokenValidationContextHolder: TokenValidationContextHolder): String? =
        try {
            tokenValidationContextHolder.getTokenValidationContext()
                .getJwtToken(AZURE_AD_ISSUER)
                ?.jwtTokenClaims
                ?.getStringClaim(NAV_IDENT_CLAIM)
        } catch (e: Exception) {
            logger.debug("Could not read NAVident from token context.", e)
            null
        }
}

open class KlageUnleashProxyContext(
    open val navIdent: String?,
    open val appName: String,
    open val podName: String,
)
