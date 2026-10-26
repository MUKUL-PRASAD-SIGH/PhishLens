package com.phishlens.scanapi.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.phishlens.scanapi.config.AppInfoProperties;
import com.phishlens.scanapi.scan.ScanHistory;
import com.phishlens.scanapi.scan.ScanRecord;
import com.phishlens.scanapi.scan.ScanService;
import com.phishlens.scanapi.scoring.Verdict;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ScanController.class)
@Import(ScanHistory.class)
@EnableConfigurationProperties(AppInfoProperties.class)
// ScanHistory is a real singleton bean; a fresh context per test keeps tests independent.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ScanControllerTest {

    private static final String URL = "http://192.168.10.5/secure-login/verify@account-update.xyz";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ScanHistory history;

    @MockitoBean
    private ScanService scanService;

    private static ScanRecord record(String url, int second) {
        return new ScanRecord(url, new BigDecimal("0.91"), Verdict.MALICIOUS, "model", "v2",
                Instant.parse("2026-10-22T09:00:00Z").plusSeconds(second));
    }

    private static String body(String url) {
        return "{\"url\":\"" + url + "\"}";
    }

    @Test
    void scanReturnsTheContractShape() throws Exception {
        when(scanService.scan(URL)).thenReturn(record(URL, 0));

        mvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON).content(body(URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.score").value(0.91))
                .andExpect(jsonPath("$.verdict").value("MALICIOUS"))
                .andExpect(jsonPath("$.source").value("model"))
                .andExpect(jsonPath("$.modelVersion").value("v2"))
                .andExpect(jsonPath("$.scannedAt").value("2026-10-22T09:00:00Z"))
                .andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    void urlWithoutSchemeIsAccepted() throws Exception {
        when(scanService.scan("example.com/login")).thenReturn(record("example.com/login", 0));

        mvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON).content(body("example.com/login")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"url\":null}", "{\"url\":\"\"}", "{\"url\":\"   \"}"})
    void missingOrBlankUrlIs400(String json) throws Exception {
        mvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("url must not be blank"));
        verify(scanService, never()).scan(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "ftp://example.com", "localhost", "http://"})
    void malformedUrlIs400(String url) throws Exception {
        mvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON).content(body(url)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("url must be an http or https URL with a valid host"));
        verify(scanService, never()).scan(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{\"url\":", "[]"})
    void unreadableBodyIs400(String json) throws Exception {
        mvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verify(scanService, never()).scan(anyString());
    }

    @Test
    void recentScansAreNewestFirst() throws Exception {
        history.add(record("http://example.com/1", 1));
        history.add(record("http://example.com/2", 2));
        history.add(record("http://example.com/3", 3));

        mvc.perform(get("/api/scans").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].url").value("http://example.com/3"))
                .andExpect(jsonPath("$[0].scannedAt").value("2026-10-22T09:00:03Z"))
                .andExpect(jsonPath("$[1].url").value("http://example.com/2"));
    }

    @Test
    void limitDefaultsTo20AndIsClampedTo1Through100() throws Exception {
        for (int n = 1; n <= 120; n++) {
            history.add(record("http://example.com/" + n, n));
        }
        mvc.perform(get("/api/scans")).andExpect(jsonPath("$", hasSize(20)));
        mvc.perform(get("/api/scans").param("limit", "999")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(100)));
        mvc.perform(get("/api/scans").param("limit", "0")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/scans").param("limit", "-5")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void nonNumericLimitIs400() throws Exception {
        mvc.perform(get("/api/scans").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be a number"));
    }

    @Test
    void infoShowsServiceVersionAndCommit() throws Exception {
        mvc.perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("scan-api"))
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.commit").value("local"));
    }
}
