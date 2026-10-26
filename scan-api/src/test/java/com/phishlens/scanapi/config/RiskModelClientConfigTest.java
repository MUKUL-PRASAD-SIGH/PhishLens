package com.phishlens.scanapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phishlens.scanapi.model.RiskModelClient;
import com.phishlens.scanapi.model.RiskModelUnavailableException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The real HTTP client honours the timeout budget. A loopback socket that accepts
 * connections but never answers stands in for a hung risk-model.
 */
class RiskModelClientConfigTest {

    @Test
    void hungModelFallsBackWithinTheBudget() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            ModelProperties props = new ModelProperties("http://127.0.0.1:" + silent.getLocalPort(), 800);
            RiskModelClient client = new RiskModelClient(
                    new RiskModelClientConfig().riskModelRestClient(RestClient.builder(), props));

            long started = System.nanoTime();
            assertThatThrownBy(() -> client.predict("http://example.com"))
                    .isInstanceOf(RiskModelUnavailableException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            // Connect succeeds at once, so the 500 ms read timeout is what fires.
            assertThat(elapsed).isBetween(Duration.ofMillis(450), Duration.ofMillis(1500));
        }
    }
}
