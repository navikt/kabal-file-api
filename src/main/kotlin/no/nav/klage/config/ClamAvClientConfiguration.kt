package no.nav.klage.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class ClamAvClientConfiguration {

    @Value($$"${CLAM_AV_URL}")
    private lateinit var url: String

    @Bean
    fun clamAvWebClient(): WebClient {
        //Scanning large files (up to 512 MB) can take a while, so allow a generous response timeout.
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(Duration.ofSeconds(300))

        return WebClient.builder()
            .baseUrl(url)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { configurer ->
                configurer
                    .defaultCodecs()
                    .maxInMemorySize(16 * 1024 * 1024)
            }
            .build()
    }
}
