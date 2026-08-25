package no.nav.klage.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class AsyncConfiguration {
    companion object {
        const val DOCUMENT_DELETE_EXECUTOR = "documentDeleteExecutor"
    }

    /**
     * Deletion of the underlying GCS object is fire and forget, so no client is waiting for it to
     * finish. CallerRunsPolicy means we fall back to deleting on the request thread if the queue is
     * full, which is slow but never silently drops a deletion.
     */
    @Bean(DOCUMENT_DELETE_EXECUTOR)
    fun documentDeleteExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 8
            queueCapacity = 1000
            setThreadNamePrefix("document-delete-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            // Let queued deletions finish when the pod is shutting down.
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(20)
            initialize()
        }
}
