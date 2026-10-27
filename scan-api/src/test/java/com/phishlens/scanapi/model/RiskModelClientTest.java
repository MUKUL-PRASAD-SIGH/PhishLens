package com.phishlens.scanapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RiskModelClientTest {

    private static final String URL = "http://192.168.10.5/secure-login/verify@account-update.xyz";

    private MockRestServiceServer server;
    private RiskModelClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://risk-model:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RiskModelClient(builder.build());
    }

    @Test
    void returnsScoreAndVersionAndIgnoresUnknownFields() {
        server.expect(requestTo("http://risk-model:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"url\":\"" + URL + "\"}"))
                .andRespond(withSuccess("""
                        {"score": 0.91, "modelVersion": "v2",
                         "features": {"length": 58, "has_ip_host": true},
                         "addedLater": "ignored"}""", MediaType.APPLICATION_JSON));

        PredictResponse response = client.predict(URL);

        assertThat(response.score()).isEqualTo(0.91);
        assertThat(response.modelVersion()).isEqualTo("v2");
        server.verify();
    }

    @Test
    void readTimeoutIsUnavailable() {
        server.expect(requestTo("http://risk-model:8000/predict"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> client.predict(URL)).isInstanceOf(RiskModelUnavailableException.class);
    }

    @Test
    void connectionRefusedIsUnavailable() {
        server.expect(requestTo("http://risk-model:8000/predict"))
                .andRespond(withException(new ConnectException("Connection refused")));
        assertThatThrownBy(() -> client.predict(URL)).isInstanceOf(RiskModelUnavailableException.class);
    }

    @Test
    void serverErrorIsUnavailable() {
        server.expect(requestTo("http://risk-model:8000/predict")).andRespond(withServerError());
        assertThatThrownBy(() -> client.predict(URL)).isInstanceOf(RiskModelUnavailableException.class);
    }

    @Test
    void modelNotLoadedIsUnavailable() {
        server.expect(requestTo("http://risk-model:8000/predict"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.predict(URL)).isInstanceOf(RiskModelUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"modelVersion\": \"v2\"}",
            "{\"score\": 1.5, \"modelVersion\": \"v2\"}",
            "{\"score\": 0.5}",
            "not json"})
    void unusableBodyIsUnavailable(String body) {
        server.expect(requestTo("http://risk-model:8000/predict"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.predict(URL)).isInstanceOf(RiskModelUnavailableException.class);
    }
}
