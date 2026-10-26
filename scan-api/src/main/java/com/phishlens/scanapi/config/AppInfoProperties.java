package com.phishlens.scanapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Build identity shown by /api/info. Bound from APP_VERSION and APP_COMMIT
 * (see application.yml), falling back to dev / local.
 */
@ConfigurationProperties(prefix = "phishlens.app")
public record AppInfoProperties(String version, String commit) {
}
