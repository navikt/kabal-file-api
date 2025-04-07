package no.nav.klage

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.Cors
import com.google.cloud.storage.HttpMethod
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration
class GCSStorage(
    @Value("\${GCS_CREDENTIALS}")
    val gcsCredentials: String,
    @Value("\${GCS_BUCKET}")
    private val bucket: String,
) {

    @Bean
    fun gcsStorage(): Storage {
        val storage = StorageOptions.newBuilder()
            .setCredentials(GoogleCredentials.fromStream(gcsCredentials.byteInputStream()))
            .build()
            .service

        val cors: Cors =
            Cors.newBuilder()
                .setOrigins(
                    listOf(
                        Cors.Origin.of("https://kabal.intern.dev.nav.no"),
                    )
                )
                .setMethods(listOf(HttpMethod.GET))
                .setResponseHeaders(listOf("Content-Type"))
                .setMaxAgeSeconds(3600)
                .build()

        val bucket = storage.get(bucket)

        bucket.toBuilder().setCors(listOf(cors)).build().update()

        return storage
    }

}