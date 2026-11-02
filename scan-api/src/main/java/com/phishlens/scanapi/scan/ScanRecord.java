package com.phishlens.scanapi.scan;

import com.phishlens.scanapi.scoring.Verdict;
import java.math.BigDecimal;
import java.time.Instant;

/** One completed scan, as kept in ScanHistory. */
public record ScanRecord(
        String url,
        BigDecimal score,
        Verdict verdict,
        String source,
        String modelVersion,
        Instant scannedAt) {

    public static final String SOURCE_MODEL = "model";
    public static final String SOURCE_FALLBACK = "fallback";
    public static final String FALLBACK_MODEL_VERSION = "fallback";
}
