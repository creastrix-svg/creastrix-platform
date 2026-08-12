package com.creastrix.platform.organization.domain;

import java.util.UUID;

/** Raised when no Organization exists for the requested identity. */
public class OrganizationNotFoundException extends RuntimeException {

    private final UUID organizationId;

    public OrganizationNotFoundException(UUID organizationId) {
        super("Organization %s not found".formatted(organizationId));
        this.organizationId = organizationId;
    }

    public UUID organizationId() {
        return organizationId;
    }
}
