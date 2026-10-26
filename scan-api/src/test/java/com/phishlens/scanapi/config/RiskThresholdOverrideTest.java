package com.phishlens.scanapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.phishlens.scanapi.scoring.Verdict;
import com.phishlens.scanapi.scoring.VerdictPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The ConfigMap demo end to end: RISK_THRESHOLD=0.60 changes the verdict with no code change. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "RISK_THRESHOLD=0.60")
class RiskThresholdOverrideTest {

    @Autowired
    private VerdictPolicy policy;

    @Test
    void borderlineScoreBecomesMalicious() {
        assertThat(policy.verdictFor(new BigDecimal("0.65"))).isEqualTo(Verdict.MALICIOUS);
        assertThat(policy.verdictFor(new BigDecimal("0.59"))).isEqualTo(Verdict.SUSPICIOUS);
    }
}
