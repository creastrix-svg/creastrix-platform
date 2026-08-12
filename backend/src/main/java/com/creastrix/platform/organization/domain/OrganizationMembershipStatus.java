package com.creastrix.platform.organization.domain;

/**
 * Status of an Organization Membership.
 *
 * <p>ACTIVE is the only Organization Membership status whose semantics are
 * defined and required by the approved specification (Organization Membership
 * APPROVED 1.4). Additional statuses and lifecycle transitions require
 * explicit future specification before use.
 */
public enum OrganizationMembershipStatus {
    ACTIVE
}
