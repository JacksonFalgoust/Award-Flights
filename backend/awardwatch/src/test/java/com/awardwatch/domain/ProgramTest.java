package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.Arrays;

class ProgramTest {

    @ParameterizedTest
    @EnumSource(Program.class)
    void everyProgramHasADisplayName(Program program) {
        assertThat(program.displayName()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(Program.class)
    void displayNameIsNotTheConstantName(Program program) {
        assertThat(program.displayName()).isNotEqualTo(program.name());
    }

    @Test
    void displayNamesAreDistinct() {
        assertThat(Arrays.stream(Program.values()).map(Program::displayName)).doesNotHaveDuplicates();
    }
}
