package com.creastrix.platform.workspace.domain;

import java.util.UUID;

/**
 * Raised when Organization-owned Workspace creation is attempted by a creator
 * User who does not have an ACTIVE Organization Membership with the role OWNER
 * in the owning Organization.
 */
public class WorkspaceCreatorNotOrganizationOwnerException extends RuntimeException {

    private final UUID organizationId;
    private final UUID creatorUserId;

    public WorkspaceCreatorNotOrganizationOwnerException(UUID organizationId, UUID creatorUserId) {
        super(("Organization-owned Workspace creation requires an ACTIVE OWNER Organization "
                + "Membership in Organization %s, but User %s has none")
                .formatted(organizationId, creatorUserId));
        this.organizationId = organizationId;
        this.creatorUserId = creatorUserId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID creatorUserId() {
        return creatorUserId;
    }
}
