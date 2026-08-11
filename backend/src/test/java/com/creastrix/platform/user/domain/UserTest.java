package com.creastrix.platform.user.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class UserTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "ACTIVE, SUSPENDED",
            "ACTIVE, DEACTIVATED",
            "SUSPENDED, ACTIVE",
            "SUSPENDED, DEACTIVATED"
    })
    void allowedTransitionChangesStatusAndPreservesIdentity(UserStatus from, UserStatus target) {
        User user = new User(ID, from);

        User transitioned = user.transitionTo(target);

        assertThat(transitioned.id()).isEqualTo(ID);
        assertThat(transitioned.status()).isEqualTo(target);
        assertThat(user.status()).isEqualTo(from);
    }

    @ParameterizedTest(name = "{0} -> {1} rejected")
    @CsvSource({
            "DEACTIVATED, ACTIVE",
            "DEACTIVATED, SUSPENDED",
            "DEACTIVATED, DEACTIVATED",
            "ACTIVE, ACTIVE",
            "SUSPENDED, SUSPENDED"
    })
    void unsupportedTransitionIsRejected(UserStatus from, UserStatus target) {
        User user = new User(ID, from);

        assertThatExceptionOfType(InvalidUserStatusTransitionException.class)
                .isThrownBy(() -> user.transitionTo(target))
                .satisfies(exception -> {
                    assertThat(exception.userId()).isEqualTo(ID);
                    assertThat(exception.currentStatus()).isEqualTo(from);
                    assertThat(exception.targetStatus()).isEqualTo(target);
                });

        assertThat(user.id()).isEqualTo(ID);
        assertThat(user.status()).isEqualTo(from);
    }

    @Test
    void nullTargetIsRejected() {
        User user = new User(ID, UserStatus.ACTIVE);

        assertThatNullPointerException()
                .isThrownBy(() -> user.transitionTo(null))
                .withMessage("Target User status must not be null");
    }

    @Test
    void nullConstructorArgumentsAreRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new User(null, UserStatus.ACTIVE))
                .withMessage("User id must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new User(ID, null))
                .withMessage("User status must not be null");
    }
}
