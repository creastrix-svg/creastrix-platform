package com.creastrix.platform.workspace.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A Workspace: an operational, ownership, and authorization boundary for
 * collaborative work, with a stable platform identity and exactly one
 * immutable owner that is either one User or one Organization.
 *
 * <p>This slice models only the stable UUID identity and the single owner.
 * Name, status, lifecycle, timestamps, Created By, and business metadata are
 * not defined by the approved specification for this slice and therefore do
 * not belong to this type.
 */
public record Workspace(UUID id, WorkspaceOwnerType ownerType, UUID ownerId) {

    public Workspace {
        Objects.requireNonNull(id, "Workspace id must not be null");
        Objects.requireNonNull(ownerType, "Workspace ownerType must not be null");
        Objects.requireNonNull(ownerId, "Workspace ownerId must not be null");
    }
}
