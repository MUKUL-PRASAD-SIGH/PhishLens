package com.phishlens.scanapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The part of risk-model's /predict response that scan-api uses. Unknown fields
 * (features, anything M2 adds later) are ignored so the model can evolve freely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PredictResponse(Double score, String modelVersion) {
}
