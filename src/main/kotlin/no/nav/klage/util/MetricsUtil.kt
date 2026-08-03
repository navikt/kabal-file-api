package no.nav.klage.util

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

inline fun <T> measureDuration(block: () -> T): Pair<T, Duration> {
    val start = System.nanoTime()
    val result = block()
    return result to Duration.ofNanos(System.nanoTime() - start)
}

fun MeterRegistry.recordTimer(name: String, duration: Duration, vararg tags: String) {
    Timer.builder(name)
        .tags(*tags)
        .register(this)
        .record(duration)
}

fun MeterRegistry.recordDistribution(name: String, value: Double, baseUnit: String? = null, vararg tags: String) {
    DistributionSummary.builder(name)
        .baseUnit(baseUnit)
        .tags(*tags)
        .register(this)
        .record(value)
}
