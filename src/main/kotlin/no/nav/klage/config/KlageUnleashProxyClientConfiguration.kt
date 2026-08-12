package no.nav.klage.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
@Profile("dev")
class KlageUnleashProxyClientConfiguration {

    @Value($$"${KLAGE_UNLEASH_PROXY_URL}")
    private lateinit var klageUnleashProxyURL: String

    @Bean
    fun klageUnleashProxyWebClient(): WebClient {
        //Short timeouts: a slow or unavailable proxy must never hold up a document upload.
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
            .responseTimeout(Duration.ofSeconds(3))

        return WebClient.builder()
            .baseUrl(klageUnleashProxyURL)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
