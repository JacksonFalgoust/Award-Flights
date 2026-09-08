package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.Arrays;

class CabinTest {

    @Test
    void economyIsY() {
        assertThat(Cabin.ECONOMY.code()).isEqualTo('Y');
    }

    @Test
    void premiumEconomyIsW() {
        assertThat(Cabin.PREMIUM_ECONOMY.code()).isEqualTo('W');
    }

    @Test
    void businessIsJ() {
        assertThat(Cabin.BUSINESS.code()).isEqualTo('J');
    }

    @Test
    void firstIsF() {
        assertThat(Cabin.FIRST.code()).isEqualTo('F');
    }

    @Test
    void hasExactlyFourCabins() {
        assertThat(Cabin.values()).hasSize(4);
    }

    @Test
    void codesAreDistinct() {
        assertThat(Arrays.stream(Cabin.values()).map(Cabin::code)).doesNotHaveDuplicates();
    }
}
