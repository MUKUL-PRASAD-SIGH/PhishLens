package com.phishlens.scanapi.scoring;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Local scoring used when risk-model is unavailable. Java port of
 * risk-model/app/scorer.py::score_v1; the weights are V1_WEIGHTS from that file.
 */
@Component
public class FallbackScorer {

    private static final double BIAS = 0.05;
    private static final double HAS_IP_HOST = 0.30;
    private static final double HAS_AT_SYMBOL = 0.20;
    private static final double SUSPICIOUS_TLD = 0.15;
    private static final double NOT_HTTPS = 0.10;
    private static final double PER_SUBDOMAIN = 0.05;
    private static final int MAX_SUBDOMAINS = 3;
    private static final double LENGTH = 0.10;
    private static final double DIGITS = 0.10;

    public BigDecimal score(String url) {
        return ScoreRounding.round(rawScore(UrlFeatureExtractor.extract(url)));
    }

    /** Weighted sum clamped to [0, 1]; terms are added in score_v1's order. */
    double rawScore(UrlFeatures f) {
        double s = BIAS
                + HAS_IP_HOST * flag(f.hasIpHost())
                + HAS_AT_SYMBOL * flag(f.hasAtSymbol())
                + SUSPICIOUS_TLD * flag(f.suspiciousTld())
                + NOT_HTTPS * flag(!f.usesHttps())
                + PER_SUBDOMAIN * Math.min(f.numSubdomains(), MAX_SUBDOMAINS)
                + LENGTH * Math.min(f.length() / 100.0, 1)
                + DIGITS * Math.min(f.numDigits() / 10.0, 1);
        return Math.max(0.0, Math.min(1.0, s));
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }
}
