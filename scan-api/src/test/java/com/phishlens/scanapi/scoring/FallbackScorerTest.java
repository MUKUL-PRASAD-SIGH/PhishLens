package com.phishlens.scanapi.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FallbackScorerTest {

    private static final String LONG_IP_URL = "http://1.1.1.1/" + "9".repeat(500) + "@x.xyz";

    private final FallbackScorer scorer = new FallbackScorer();

    // Expected: round(score_v1(extract_features(url)), 2) from risk-model, computed in Python
    // and hardcoded here (raw Python value in the comment).
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "http://192.168.10.5/secure-login/verify@account-update.xyz | 0.95",  // 0.9480000000000001
            "https://www.google.com                                     | 0.12",  // 0.12200000000000001
            "example.com/login                                          | 0.17",  // 0.16700000000000004
            "https://a.b.c.d.example.tk/login?id=12345678               | 0.47",  // 0.47400000000000003
            "http://user@login.secure-bank.com.verify.top:8080/a?b=1    | 0.76",  // 0.7550000000000001
            "HTTPS://Sub.Example.ZIP/x                                  | 0.28",  // 0.275
            "http://example.com/redirect?to=evil.xyz#frag               | 0.34"}) // 0.34400000000000003
    void matchesPythonV1(String url, String python) {
        assertThat(scorer.score(url)).isEqualTo(new BigDecimal(python));
    }

    @Test
    void clampsAtOne() {
        // Python: score_v1 == 1.0 (the unclamped sum is 1.15).
        assertThat(scorer.score(LONG_IP_URL)).isEqualTo(new BigDecimal("1.00"));
        assertThat(scorer.rawScore(UrlFeatureExtractor.extract(LONG_IP_URL))).isEqualTo(1.0);
    }

    @Test
    void clampsAtZero() {
        // Every v1 weight is positive, so no real URL scores below the 0.05 bias; negative
        // inputs exercise the lower clamp of max(0.0, ...).
        UrlFeatures impossible = new UrlFeatures(-1000, -1000, false, false, 0, false, true);
        assertThat(scorer.rawScore(impossible)).isEqualTo(0.0);
    }

    @Test
    void lowestRealScoreIsTheBias() {
        UrlFeatures cleanest = new UrlFeatures(0, 0, false, false, 0, false, true);
        assertThat(scorer.rawScore(cleanest)).isEqualTo(0.05);
    }
}
