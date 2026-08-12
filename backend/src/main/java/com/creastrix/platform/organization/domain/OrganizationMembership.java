package com.creastrix.platform.organization.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An Organization Membership: the relationship between exactly one User and
 * exactly one Organization, with the assigned role and membership status.
 *
 * <p>The approved specification defines Membership uniqueness through the
 * combination of Organization and User. No independent Membership identity
 * exists, so none is invented here.
 */
public record OrganizationMembership(
        UUID organizationId,
        UUID userId,
        OrganizationRole role,
        OrganizationMembershipStatus status) {

    public OrganizationMembership {
        Objects.requireNonNull(organizationId, "Organization Membership organizationId must not be null");
        Objects.requireNonNull(userId, "Organization Membership userId must not be null");
        Objects.requireNonNull(role, "Organization Membership role must not be null");
        Objects.requireNonNull(status, "Organization Membership status must not be null");
    }
}
