package com.phishlens.scanapi.scan;

import com.phishlens.scanapi.metrics.ScanMetrics;
import com.phishlens.scanapi.model.PredictResponse;
import com.phishlens.scanapi.model.RiskModelClient;
import com.phishlens.scanapi.model.RiskModelUnavailableException;
import com.phishlens.scanapi.scoring.FallbackScorer;
import com.phishlens.scanapi.scoring.ScoreRounding;
import com.phishlens.scanapi.scoring.Verdict;
import com.phishlens.scanapi.scoring.VerdictPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Scores a URL with risk-model, falling back to local rules, and records the result. */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final RiskModelClient riskModelClient;
    private final FallbackScorer fallbackScorer;
    private final VerdictPolicy verdictPolicy;
    private final ScanHistory history;
    private final ScanMetrics metrics;
    private final Clock clock;

    public ScanService(RiskModelClient riskModelClient, FallbackScorer fallbackScorer,
            VerdictPolicy verdictPolicy, ScanHistory history, ScanMetrics metrics, Clock clock) {
        this.riskModelClient = riskModelClient;
        this.fallbackScorer = fallbackScorer;
        this.verdictPolicy = verdictPolicy;
        this.history = history;
        this.metrics = metrics;
        this.clock = clock;
    }

    public ScanRecord scan(String rawUrl) {
        String url = rawUrl.strip();
        BigDecimal score;
        String source;
        String modelVersion;
        long started = System.nanoTime();
        try {
            PredictResponse prediction = riskModelClient.predict(url);
            metrics.recordModelCall(System.nanoTime() - started, true);
            score = ScoreRounding.round(prediction.score());
            source = ScanRecord.SOURCE_MODEL;
            modelVersion = prediction.modelVersion();
        } catch (RiskModelUnavailableException e) {
            metrics.recordModelCall(System.nanoTime() - started, false);
            metrics.recordFallback();
            log.warn("risk-model unavailable, using fallback scoring: {}", e.getMessage());
            score = fallbackScorer.score(url);
            source = ScanRecord.SOURCE_FALLBACK;
            modelVersion = ScanRecord.FALLBACK_MODEL_VERSION;
        }
        Verdict verdict = verdictPolicy.verdictFor(score);
        ScanRecord record = new ScanRecord(url, score, verdict, source, modelVersion,
                Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
        history.add(record);
        metrics.recordScan(verdict);
        return record;
    }
}
