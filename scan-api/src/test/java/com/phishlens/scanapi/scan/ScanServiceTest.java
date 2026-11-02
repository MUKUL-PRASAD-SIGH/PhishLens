package com.phishlens.scanapi.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.phishlens.scanapi.config.VerdictProperties;
import com.phishlens.scanapi.metrics.ScanMetrics;
import com.phishlens.scanapi.model.RiskModelClient;
import com.phishlens.scanapi.scoring.FallbackScorer;
import com.phishlens.scanapi.scoring.Verdict;
import com.phishlens.scanapi.scoring.VerdictPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/** Model call, fallback, verdict, history and metrics together; risk-model is mocked. */
class ScanServiceTest {

    private static final String URL = "http://192.168.10.5/secure-login/verify@account-update.xyz";
    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00.789Z");

    private MockRestServiceServer server;
    private ScanHistory history;
    private SimpleMeterRegistry registry;
    private ScanService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://risk-model:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        history = new ScanHistory();
        registry = new SimpleMeterRegistry();
        VerdictPolicy policy = new VerdictPolicy(
                new VerdictProperties(new BigDecimal("0.40"), new BigDecimal("0.70")));
        service = new ScanService(new RiskModelClient(builder.build()), new FallbackScorer(), policy,
                history, new ScanMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void usesTheModelWhenItAnswers() {
        server.expect(requestTo("http://risk-model:8000/predict"))
                .andRespond(withSuccess("{\"score\":0.91,\"modelVersion\":\"v2\"}", MediaType.APPLICATION_JSON));

        ScanRecord record = service.scan("  " + URL + "  ");

        assertThat(record).isEqualTo(new ScanRecord(URL, new BigDecimal("0.91"), Verdict.MALICIOUS,
                "model", "v2", Instant.parse("2026-10-22T09:00:00Z")));
        assertThat(history.recent(10)).containsExactly(record);
        assertThat(registry.counter("phishlens.scans", "verdict", "MALICIOUS").count()).isEqualTo(1);
        assertThat(registry.counter("phishlens.model.fallback").count()).isZero();
        assertThat(registry.timer("phishlens.model.latency", "outcome", "success").count()).isEqualTo(1);
    }

    static Stream<Arguments> modelFailures() {
        return Stream.of(
                Arguments.of("timeout", withException(new SocketTimeoutException("Read timed out"))),
                Arguments.of("connection refused", withException(new ConnectException("Connection refused"))),
                Arguments.of("500", withServerError()));
    }

    @ParameterizedTest(name = "{0} -> fallback")
    @MethodSource("modelFailures")
    void fallsBackWhenTheModelFails(String failure, ResponseCreator response) {
        server.expect(requestTo("http://risk-model:8000/predict")).andRespond(response);

        ScanRecord record = service.scan(URL);

        // 0.95 is risk-model's v1 score for this URL.
        assertThat(record).isEqualTo(new ScanRecord(URL, new BigDecimal("0.95"), Verdict.MALICIOUS,
                "fallback", "fallback", Instant.parse("2026-10-22T09:00:00Z")));
        assertThat(history.recent(10)).containsExactly(record);
        assertThat(registry.counter("phishlens.model.fallback").count()).isEqualTo(1);
        assertThat(registry.timer("phishlens.model.latency", "outcome", "error").count()).isEqualTo(1);
        assertThat(registry.counter("phishlens.scans", "verdict", "MALICIOUS").count()).isEqualTo(1);
    }
}
