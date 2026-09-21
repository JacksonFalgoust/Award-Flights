package com.awardwatch.ingest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeatsAeroPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void validConfigurationHasNoViolations() {
        SeatsAeroProperties properties = new SeatsAeroProperties(
                "https://seats.aero/partnerapi/",
                "test-key-not-a-secret"
        );

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void nullBaseUrlIsRejectedByValidation() {
        assertSingleViolation(
                new SeatsAeroProperties(null, "test-key-not-a-secret"),
                "baseUrl"
        );
    }

    @Test
    void blankBaseUrlIsRejectedByValidation() {
        assertSingleViolation(
                new SeatsAeroProperties("   ", "test-key-not-a-secret"),
                "baseUrl"
        );
    }

    @Test
    void nullApiKeyIsRejectedByValidation() {
        assertSingleViolation(
                new SeatsAeroProperties("https://seats.aero/partnerapi/", null),
                "apiKey"
        );
    }

    @Test
    void blankApiKeyIsRejectedByValidation() {
        assertSingleViolation(
                new SeatsAeroProperties("https://seats.aero/partnerapi/", "\t"),
                "apiKey"
        );
    }

    private static void assertSingleViolation(
            SeatsAeroProperties properties,
            String expectedProperty
    ) {
        Set<ConstraintViolation<SeatsAeroProperties>> violations = validator.validate(properties);

        assertThat(violations)
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo(expectedProperty);
                    assertThat(violation.getMessage()).isEqualTo("must not be blank");
                });
    }
}
