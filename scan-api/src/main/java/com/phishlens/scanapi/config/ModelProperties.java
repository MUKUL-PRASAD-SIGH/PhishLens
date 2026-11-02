package com.phishlens.scanapi.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * risk-model connection settings, bound from MODEL_URL and MODEL_TIMEOUT_MS.
 *
 * <p>MODEL_TIMEOUT_MS is the whole budget before falling back: 300 ms of it is the
 * connect timeout and the rest is the read timeout, so the default 800 gives 300 + 500.
 */
@Validated
@ConfigurationProperties(prefix = "phishlens.model")
public record ModelProperties(
        @NotBlank String url,
        @Min(value = CONNECT_TIMEOUT_MS + 1,
                message = "MODEL_TIMEOUT_MS must be at least 301: 300 ms of it is reserved for"
                        + " the connect timeout and the rest is the read timeout")
        long timeoutMs) {

    static final long CONNECT_TIMEOUT_MS = 300;

    public Duration connectTimeout() {
        return Duration.ofMillis(CONNECT_TIMEOUT_MS);
    }

    public Duration readTimeout() {
        return Duration.ofMillis(timeoutMs - CONNECT_TIMEOUT_MS);
    }
}
