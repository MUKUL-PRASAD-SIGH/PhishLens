package com.phishlens.scanapi.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.phishlens.scanapi.config.VerdictProperties;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VerdictPolicyTest {

    private static VerdictPolicy policy(String riskThreshold) {
        return new VerdictPolicy(new VerdictProperties(new BigDecimal("0.40"), new BigDecimal(riskThreshold)));
    }

    @ParameterizedTest(name = "{0} -> {1} at RISK_THRESHOLD=0.70")
    @CsvSource({
            "0.00, SAFE",
            "0.39, SAFE",
            "0.40, SUSPICIOUS",
            "0.69, SUSPICIOUS",
            "0.70, MALICIOUS",
            "1.00, MALICIOUS"})
    void defaultThresholdBoundaries(String score, Verdict expected) {
        assertThat(policy("0.70").verdictFor(new BigDecimal(score))).isEqualTo(expected);
    }

    // The ConfigMap demo: lowering RISK_THRESHOLD to 0.60 turns a borderline 0.65 MALICIOUS.
    @ParameterizedTest(name = "{0} -> {1} at RISK_THRESHOLD=0.60")
    @CsvSource({
            "0.39, SAFE",
            "0.59, SUSPICIOUS",
            "0.60, MALICIOUS",
            "0.65, MALICIOUS"})
    void loweredThreshold(String score, Verdict expected) {
        assertThat(policy("0.60").verdictFor(new BigDecimal(score))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "0.65 at RISK_THRESHOLD={0} -> {1}")
    @CsvSource({"0.70, SUSPICIOUS", "0.60, MALICIOUS"})
    void sameScoreChangesVerdictWithThreshold(String riskThreshold, Verdict expected) {
        assertThat(policy(riskThreshold).verdictFor(new BigDecimal("0.65"))).isEqualTo(expected);
    }

    // Raw doubles that land just below a boundary still compare exactly once rounded.
    @ParameterizedTest(name = "raw {0} -> {1}")
    @CsvSource({
            "0.6999999999999999, MALICIOUS",
            "0.3999999999999999, SUSPICIOUS",
            "0.394, SAFE",
            "0.695, SUSPICIOUS"})
    void roundedDoublesHitBoundariesExactly(double raw, Verdict expected) {
        assertThat(policy("0.70").verdictFor(ScoreRounding.round(raw))).isEqualTo(expected);
    }
}
