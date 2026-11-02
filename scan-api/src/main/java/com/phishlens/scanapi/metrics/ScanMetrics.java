package com.phishlens.scanapi.metrics;

import com.phishlens.scanapi.scoring.Verdict;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * PhishLens metrics from docs/SPEC.md 2. Prometheus names: phishlens_scans_total{verdict},
 * phishlens_model_fallback_total and phishlens_model_latency_seconds{outcome}.
 */
@Component
public class ScanMetrics {

    private final Map<Verdict, Counter> scansByVerdict = new EnumMap<>(Verdict.class);
    private final Counter fallbacks;
    private final Timer modelLatencySuccess;
    private final Timer modelLatencyError;

    public ScanMetrics(MeterRegistry registry) {
        // Registered up front so every verdict shows up as 0 before the first scan.
        for (Verdict verdict : Verdict.values()) {
            scansByVerdict.put(verdict, Counter.builder("phishlens.scans")
                    .description("Scans completed, by verdict")
                    .tag("verdict", verdict.name())
                    .register(registry));
        }
        fallbacks = Counter.builder("phishlens.model.fallback")
                .description("Scans scored by the local fallback because risk-model was unavailable")
                .register(registry);
        modelLatencySuccess = modelLatency(registry, "success");
        modelLatencyError = modelLatency(registry, "error");
    }

    public void recordScan(Verdict verdict) {
        scansByVerdict.get(verdict).increment();
    }

    public void recordFallback() {
        fallbacks.increment();
    }

    /** Time of one risk-model call, including calls that failed or timed out. */
    public void recordModelCall(long nanos, boolean succeeded) {
        (succeeded ? modelLatencySuccess : modelLatencyError).record(nanos, TimeUnit.NANOSECONDS);
    }

    private static Timer modelLatency(MeterRegistry registry, String outcome) {
        return Timer.builder("phishlens.model.latency")
                .description("Time spent calling risk-model")
                .tag("outcome", outcome)
                .register(registry);
    }
}
