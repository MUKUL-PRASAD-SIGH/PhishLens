package com.phishlens.scanapi.config;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Verdict thresholds. riskThreshold is bound from RISK_THRESHOLD; suspiciousThreshold is
 * plain config (0.40), not an environment variable. BigDecimal keeps "0.70" exact.
 */
@Validated
@ConfigurationProperties(prefix = "phishlens.verdict")
public record VerdictProperties(
        @NotNull BigDecimal suspiciousThreshold,
        @NotNull BigDecimal riskThreshold) {
}
