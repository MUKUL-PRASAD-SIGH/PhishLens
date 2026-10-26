package com.phishlens.scanapi.web;

import com.phishlens.scanapi.config.AppInfoProperties;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ScanController {

    private static final String SERVICE_NAME = "scan-api";

    private final AppInfoProperties appInfo;
    private final Clock clock;

    public ScanController(AppInfoProperties appInfo, Clock clock) {
        this.appInfo = appInfo;
        this.clock = clock;
    }

    // TODO: placeholder. Fixed SAFE response in the final contract shape; it scores nothing and
    // calls no model. Replaced in Task 3 by the real risk-model call and FallbackScorer.
    @PostMapping("/scan")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        return new ScanResponse(request.url().trim(), 0.0, "SAFE", "fallback", "fallback", now);
    }

    @GetMapping("/info")
    public InfoResponse info() {
        return new InfoResponse(SERVICE_NAME, appInfo.version(), appInfo.commit());
    }
}
