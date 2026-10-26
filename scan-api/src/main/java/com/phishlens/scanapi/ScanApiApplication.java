package com.phishlens.scanapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ScanApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScanApiApplication.class, args);
    }
}
