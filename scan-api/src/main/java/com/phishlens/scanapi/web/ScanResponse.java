package com.phishlens.scanapi.web;

import java.time.Instant;

/**
 * Response body of POST /api/scan. Field names are the docs/SPEC.md 3.1 contract.
 * scannedAt is truncated to whole seconds so it serialises as e.g. 2026-10-22T09:00:00Z.
 */
public record ScanResponse(
        String url,
        double score,
        String verdict,
        String source,
        String modelVersion,
        Instant scannedAt) {
}
