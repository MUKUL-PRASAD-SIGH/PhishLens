package com.phishlens.scanapi.web;

import com.phishlens.scanapi.config.AppInfoProperties;
import com.phishlens.scanapi.scan.ScanHistory;
import com.phishlens.scanapi.scan.ScanService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ScanController {

    private static final String SERVICE_NAME = "scan-api";

    private final ScanService scanService;
    private final ScanHistory history;
    private final AppInfoProperties appInfo;

    public ScanController(ScanService scanService, ScanHistory history, AppInfoProperties appInfo) {
        this.scanService = scanService;
        this.history = history;
        this.appInfo = appInfo;
    }

    @PostMapping("/scan")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return ScanResponse.from(scanService.scan(request.url()));
    }

    /** Newest first. Out-of-range limits are clamped to 1..100, not rejected. */
    @GetMapping("/scans")
    public List<ScanResponse> recentScans(@RequestParam(defaultValue = "20") int limit) {
        int clamped = Math.max(1, Math.min(limit, ScanHistory.CAPACITY));
        return history.recent(clamped).stream().map(ScanResponse::from).toList();
    }

    @GetMapping("/info")
    public InfoResponse info() {
        return new InfoResponse(SERVICE_NAME, appInfo.version(), appInfo.commit());
    }
}
