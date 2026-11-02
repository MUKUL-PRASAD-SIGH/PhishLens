package com.phishlens.scanapi.model;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RiskModelClient {

    private final RestClient restClient;

    public RiskModelClient(@Qualifier("riskModelRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Scores a URL with risk-model.
     *
     * @throws RiskModelUnavailableException on timeout, connection failure, any non-2xx
     *         status, or a body without a usable score and model version
     */
    public PredictResponse predict(String url) {
        PredictResponse response;
        try {
            response = restClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PredictRequest(url))
                    .retrieve()
                    .body(PredictResponse.class);
        } catch (RestClientException e) {
            throw new RiskModelUnavailableException("risk-model call failed: " + e.getMessage(), e);
        }
        if (response == null || !isUsable(response)) {
            throw new RiskModelUnavailableException("risk-model returned an unusable body: " + response);
        }
        return response;
    }

    private static boolean isUsable(PredictResponse response) {
        Double score = response.score();
        return score != null && score >= 0.0 && score <= 1.0
                && response.modelVersion() != null && !response.modelVersion().isBlank();
    }
}
