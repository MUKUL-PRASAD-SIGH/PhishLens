package com.phishlens.scanapi.model;

/** risk-model timed out, refused the connection, returned an error or an unusable body. */
public class RiskModelUnavailableException extends RuntimeException {

    public RiskModelUnavailableException(String message) {
        super(message);
    }

    public RiskModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
