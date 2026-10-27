package com.phishlens.scanapi.web;

import com.phishlens.scanapi.scan.ScanRecord;
import java.time.Instant;

/**
 * Response body of POST /api/scan and the elements of GET /api/scans. Field names are the
 * docs/SPEC.md 3.1 contract. scannedAt is whole seconds, so it serialises as e.g.
 * 2026-10-22T09:00:00Z.
 */
public record ScanResponse(
        String url,
        double score,
        String verdict,
        String source,
        String modelVersion,
        Instant scannedAt) {

    public static ScanResponse from(ScanRecord record) {
        return new ScanResponse(record.url(), record.score().doubleValue(), record.verdict().name(),
                record.source(), record.modelVersion(), record.scannedAt());
    }
}
