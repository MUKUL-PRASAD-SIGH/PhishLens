package com.phishlens.scanapi.scoring;

import com.phishlens.scanapi.config.VerdictProperties;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** docs/SPEC.md 3.1 verdict rules. Thresholds come from config, never from this class. */
@Component
public class VerdictPolicy {

    private final BigDecimal suspiciousThreshold;
    private final BigDecimal riskThreshold;

    public VerdictPolicy(VerdictProperties properties) {
        this.suspiciousThreshold = properties.suspiciousThreshold();
        this.riskThreshold = properties.riskThreshold();
    }

    /** Expects a score already rounded to 2 decimals, so 0.70 compares exactly. */
    public Verdict verdictFor(BigDecimal score) {
        if (score.compareTo(riskThreshold) >= 0) {
            return Verdict.MALICIOUS;
        }
        if (score.compareTo(suspiciousThreshold) >= 0) {
            return Verdict.SUSPICIOUS;
        }
        return Verdict.SAFE;
    }
}
