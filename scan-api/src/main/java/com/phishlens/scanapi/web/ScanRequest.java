package com.phishlens.scanapi.web;

import com.phishlens.scanapi.validation.ScanUrl;
import jakarta.validation.constraints.NotBlank;

public record ScanRequest(@NotBlank(message = "url must not be blank") @ScanUrl String url) {
}
