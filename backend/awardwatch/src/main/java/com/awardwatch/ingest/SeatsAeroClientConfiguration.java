package com.awardwatch.ingest;

import org.springframework.web.client.RestClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SeatsAeroProperties.class)
public class SeatsAeroClientConfiguration {

    @Bean
    public RestClient seatsAeroRestClient(RestClient.Builder builder, SeatsAeroProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("Partner-Authorization", properties.apiKey())
                .build();
    }
}
