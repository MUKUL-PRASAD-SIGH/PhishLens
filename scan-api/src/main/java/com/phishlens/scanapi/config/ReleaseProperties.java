package com.phishlens.scanapi.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Deliberately broken v3 for the rollback demo; this class exists only on the demo/v3-broken branch.
 *
 * <p>scan-api refuses to start unless REQUIRED_RELEASE_TOKEN is set, and the Kubernetes ConfigMap
 * does not set it, so the v3 pod goes into CrashLoopBackOff and the rollout has to be undone.
 * The value is never read or checked; any non-blank string satisfies it. It is not a secret.
 */
@Validated
@ConfigurationProperties(prefix = "phishlens.release")
public record ReleaseProperties(
        @NotBlank(message = "REQUIRED_RELEASE_TOKEN is not set. This is the deliberately broken v3"
                + " build for the rollback demo: it will not start without that variable, and the"
                + " ConfigMap does not provide it. Roll back to the previous image.")
        String requiredToken) {
}
