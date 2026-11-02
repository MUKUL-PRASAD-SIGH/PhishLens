package com.phishlens.scanapi.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RiskModelClientConfig {

    @Bean
    public RestClient riskModelRestClient(RestClient.Builder builder, ModelProperties model) {
        // HTTP/1.1 only: uvicorn does not speak h2c, so skip the upgrade attempt.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(model.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(model.readTimeout());
        return builder
                .baseUrl(model.url())
                .requestFactory(requestFactory)
                .build();
    }
}
