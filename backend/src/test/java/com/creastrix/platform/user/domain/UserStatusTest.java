package com.creastrix.platform.user.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "ACTIVE, ACTIVE, false",
            "ACTIVE, SUSPENDED, true",
            "ACTIVE, DEACTIVATED, true",
            "SUSPENDED, ACTIVE, true",
            "SUSPENDED, SUSPENDED, false",
            "SUSPENDED, DEACTIVATED, true",
            "DEACTIVATED, ACTIVE, false",
            "DEACTIVATED, SUSPENDED, false",
            "DEACTIVATED, DEACTIVATED, false"
    })
    void completeTransitionMatrix(UserStatus from, UserStatus target, boolean allowed) {
        assertThat(from.canTransitionTo(target)).isEqualTo(allowed);
    }
}
