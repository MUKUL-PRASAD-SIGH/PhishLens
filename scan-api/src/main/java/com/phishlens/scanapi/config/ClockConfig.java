package com.phishlens.scanapi.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    // UTC clock so scannedAt always ends in Z; tests replace it with a fixed clock.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
