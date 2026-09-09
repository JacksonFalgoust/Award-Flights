package com.awardwatch.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class SeatsAeroClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(SeatsAeroClientConfiguration.class);

    @Test
    void validPropertiesCreateTheClient() {
        contextRunner
                .withPropertyValues(
                        "seats-aero.base-url=https://seats.aero/partnerapi/",
                        "seats-aero.api-key=test-key-not-a-secret"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SeatsAeroProperties.class);
                    assertThat(context).hasSingleBean(RestClient.class);
                });
    }

    @Test
    void missingApiKeyPreventsClientStartup() {
        contextRunner
                .withPropertyValues("seats-aero.base-url=https://seats.aero/partnerapi/")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void blankBaseUrlPreventsClientStartup() {
        contextRunner
                .withPropertyValues(
                        "seats-aero.base-url=",
                        "seats-aero.api-key=test-key-not-a-secret"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }
}
