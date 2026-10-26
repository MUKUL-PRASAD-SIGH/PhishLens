package com.phishlens.scanapi.web;

import jakarta.validation.constraints.NotBlank;

public record ScanRequest(@NotBlank String url) {
}
