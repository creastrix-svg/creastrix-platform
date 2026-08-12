package com.creastrix.platform.organization.domain;

/**
 * Role of a User within an Organization.
 *
 * <p>OWNER is the only organizational role whose domain semantics are defined
 * by the approved specification (Organization Membership APPROVED 1.4).
 * Additional roles require explicit future specification before use.
 */
public enum OrganizationRole {
    OWNER
}
