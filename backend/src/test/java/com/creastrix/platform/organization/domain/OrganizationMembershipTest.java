package com.creastrix.platform.organization.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class OrganizationMembershipTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void nullOrganizationIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrganizationMembership(
                        null, USER_ID, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE))
                .withMessage("Organization Membership organizationId must not be null");
    }

    @Test
    void nullUserIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrganizationMembership(
                        ORGANIZATION_ID, null, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE))
                .withMessage("Organization Membership userId must not be null");
    }

    @Test
    void nullRoleIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrganizationMembership(
                        ORGANIZATION_ID, USER_ID, null, OrganizationMembershipStatus.ACTIVE))
                .withMessage("Organization Membership role must not be null");
    }

    @Test
    void nullStatusIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrganizationMembership(
                        ORGANIZATION_ID, USER_ID, OrganizationRole.OWNER, null))
                .withMessage("Organization Membership status must not be null");
    }
}
