package com.phishlens.scanapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phishlens.scanapi.ScanApiApplication;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;

/** The SPEC 3.3 defaults as bound from application.yml, with no environment overrides. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConfigurationTest {

    @Autowired
    private ModelProperties model;

    @Autowired
    private VerdictProperties verdict;

    @Autowired
    private AppInfoProperties appInfo;

    @Test
    void defaultsMatchTheSpec() {
        assertThat(model.url()).isEqualTo("http://risk-model:8000");
        assertThat(model.timeoutMs()).isEqualTo(800);
        assertThat(model.connectTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(model.readTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(verdict.suspiciousThreshold()).isEqualByComparingTo(new BigDecimal("0.40"));
        assertThat(verdict.riskThreshold()).isEqualByComparingTo(new BigDecimal("0.70"));
        assertThat(appInfo.commit()).isEqualTo("local");
        assertThat(appInfo.version()).isNotBlank().doesNotContain("@");
    }

    @Test
    void modelTimeoutOf300OrLessRefusesToStart() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(ScanApiApplication.class)
                .web(WebApplicationType.NONE)
                .properties("MODEL_TIMEOUT_MS=300");
        assertThatThrownBy(app::run)
                .rootCause()
                .hasMessageContaining("MODEL_TIMEOUT_MS must be at least 301");
    }
}
