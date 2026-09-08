package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Locale;


class AirportCodeTest {

    @Test
    void lowerCaseCanonicalizes() {
        AirportCode code = new AirportCode("atl");
	    assertThat(code.value()).isEqualTo("ATL");
    }

    @Test
    void mixedCaseCanonicalizes() {
        AirportCode code = new AirportCode("aTl");
        assertThat(code.value()).isEqualTo("ATL");
    }

    @Test
    void alreadyUpperCaseIsUnchanged() {
        AirportCode code = new AirportCode("ATL");
        assertThat(code.value()).isEqualTo("ATL");
    }

    @Test
    void canonicalizesUnderTurkishDefaultLocale() {
        Locale original = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(new AirportCode("ist").value()).isEqualTo("IST");
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"AT", "ATLL", "AT1", "", " ATL "})
    void rejectsMalformedCode(String input) {
        assertThatThrownBy(() -> new AirportCode(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRejected() {
        assertThatThrownBy(() -> new AirportCode(null)).isInstanceOf(NullPointerException.class).hasMessage("airport code cannot be null");
    }

    @Test
    void rejectionMessageReportsInputNotCanonicalized() {
        assertThatThrownBy(() -> new AirportCode("atlanta")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("atlanta").hasMessageNotContaining("ATLANTA");
    }

    @Test
    void equalsIgnoresInputCase() {
        AirportCode lower = new AirportCode("atl");
        AirportCode upper = new AirportCode("ATL");

        assertThat(lower).isEqualTo(upper);
        assertThat(lower.hashCode()).isEqualTo(upper.hashCode());
    }

    @Test
    void toStringIsBareCode() {
        AirportCode code = new AirportCode("ATL");
        assertThat(code.toString()).isEqualTo("ATL");


    }
}
