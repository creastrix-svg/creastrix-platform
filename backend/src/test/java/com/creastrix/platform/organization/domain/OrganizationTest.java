package com.creastrix.platform.organization.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class OrganizationTest {

    @Test
    void nullIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Organization(null))
                .withMessage("Organization id must not be null");
    }
}
