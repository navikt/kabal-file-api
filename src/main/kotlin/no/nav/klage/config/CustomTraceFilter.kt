package no.nav.klage.config

import io.micrometer.tracing.Tracer
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.GenericFilterBean

/**
 * Adding some custom NAV-specific attributes to standard Spring Sleuth/Micrometer
 */
@Component
@Profile("!local")
@Order(-20)
class CustomTraceFilter(
    private val tracer: Tracer,
    @Value("\${navCallIdName}") private val navCallIdName: String,
) : GenericFilterBean() {

    override fun doFilter(
        request: ServletRequest?, response: ServletResponse,
        chain: FilterChain
    ) {
        //Create if not exists
        tracer.createBaggageInScope(navCallIdName, tracer.currentTraceContext().context()!!.traceId())

        //also add this, since some services require that version/spelling
        tracer.createBaggageInScope("Nav-Call-Id", tracer.currentTraceContext().context()!!.traceId())

        chain.doFilter(request, response)
    }
}