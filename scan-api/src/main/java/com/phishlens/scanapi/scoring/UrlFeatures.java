package com.phishlens.scanapi.scoring;

/** The features.py features that the v1 weights use. */
public record UrlFeatures(
        int length,
        int numDigits,
        boolean hasIpHost,
        boolean hasAtSymbol,
        int numSubdomains,
        boolean suspiciousTld,
        boolean usesHttps) {
}
