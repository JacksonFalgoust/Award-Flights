package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteTest {
    
    @Test
    void preservesArgumentOrder() {        
        assertThat(Route.of("ATL", "NRT").origin().value()).isEqualTo("ATL");
        assertThat(Route.of("ATL", "NRT").destination().value()).isEqualTo("NRT");
    }

    @Test
    void sameAirportRejectedAcrossCase() {
        assertThatThrownBy(() -> Route.of("atl", "ATL")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameAirportMessageNamesTheRule() {
        AirportCode ATL = new AirportCode("ATL");
        assertThatThrownBy(() -> new Route(ATL, ATL)).isInstanceOf(IllegalArgumentException.class).hasMessage("origin and destination cannot be the same airport");
    }

    @Test
    void nullOriginRejected() {
        AirportCode NRT = new AirportCode("NRT");
        assertThatThrownBy(() -> new Route(null, NRT)).isInstanceOf(NullPointerException.class).hasMessage("origin cannot be null");
    }

    @Test
    void nullDestinationRejected() {
        AirportCode NRT = new AirportCode("NRT");
        assertThatThrownBy(() -> new Route(NRT, null)).isInstanceOf(NullPointerException.class).hasMessage("destination cannot be null");
    }

    @Test
    void ofRejectsNullCode() {
        assertThatThrownBy(() -> Route.of(null, "NRT")).isInstanceOf(NullPointerException.class).hasMessage("airport code cannot be null");
        assertThatThrownBy(() -> Route.of("ATL", null)).isInstanceOf(NullPointerException.class).hasMessage("airport code cannot be null");
    }

    @Test
    void ofPropagatesMalformedOrigin() {
        assertThatThrownBy(() -> Route.of("AT", "NRT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofPropagatesMalformedDestination() {
        assertThatThrownBy(() -> Route.of("ATL", "NR")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofAgreesWithConstructor() {
        assertThat(Route.of("ATL", "NRT")).isEqualTo(new Route(new AirportCode("ATL"), new AirportCode("NRT")));
    }

    @Test
    void reverseIsNotEqual() {
        assertThat(Route.of("ATL", "NRT")).isNotEqualTo(Route.of("NRT", "ATL"));
    }

    @Test
    void equalRoutesAreEqualAndHashAlike() {
        Route first = Route.of("ATL", "NRT");
        Route second = Route.of("ATL", "NRT");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void equalityIgnoresInputCase() {
        Route lower = Route.of("atl", "nrt");
        Route upper = Route.of("ATL", "NRT");

        assertThat(lower).isEqualTo(upper);
        assertThat(lower.hashCode()).isEqualTo(upper.hashCode());
    }

    @Test
    void toStringIsHyphenated() {
        assertThat(Route.of("ATL", "NRT").toString()).isEqualTo("ATL-NRT");
    }

    @Test
    void toStringPreservesDirection() {
        assertThat(Route.of("NRT", "ATL").toString()).isEqualTo("NRT-ATL");
    }

    @Test
    void toStringCanonicalizesCase() {
        assertThat(Route.of("atl", "nrt").toString()).isEqualTo("ATL-NRT");
    }
}
