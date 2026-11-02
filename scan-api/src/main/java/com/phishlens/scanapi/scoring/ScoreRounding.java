package com.phishlens.scanapi.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rounds scores to the 2 decimals of the API contract, exactly as Python's round(x, 2). */
public final class ScoreRounding {

    private ScoreRounding() {
    }

    /**
     * Uses new BigDecimal(double) on purpose, despite the usual advice to prefer
     * BigDecimal.valueOf. Python's round() rounds the exact binary value of the float, and
     * only the constructor sees that value; valueOf rounds the shortest decimal string
     * instead. Example: 0.165 is stored as 0.16500000000000000777..., so Python gives 0.17,
     * the constructor gives 0.17 and valueOf gives 0.16. Do not change this to valueOf, or
     * the fallback score drifts from risk-model's v1 score on such values.
     */
    public static BigDecimal round(double score) {
        return new BigDecimal(score).setScale(2, RoundingMode.HALF_EVEN);
    }
}
