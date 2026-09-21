package com.awardwatch.ingest;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="seats-aero")
public record SeatsAeroProperties(
    @NotBlank String baseUrl,
    @NotBlank String apiKey
) {
}