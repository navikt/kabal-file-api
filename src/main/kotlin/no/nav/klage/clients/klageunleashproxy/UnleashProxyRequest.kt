package no.nav.klage.clients.klageunleashproxy

data class UnleashProxyRequest(
    val navIdent: String,
    val appName: String,
    val podName: String,
)
