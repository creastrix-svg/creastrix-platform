package com.creastrix.platform.workspace.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.creastrix.platform.user.domain.UserStatus;

/**
 * A Workspace Membership: the scoped access relationship between exactly one
 * User and exactly one Workspace, with the assigned role, membership status,
 * and the explicitly granted permission scopes.
 *
 * <p>The approved specification defines Membership uniqueness through the
 * combination of Workspace and User. No independent Membership identity
 * exists, so none is invented here.
 *
 * <p>The granted scope set is defensively copied and immutable.
 */
public record WorkspaceMembership(
        UUID workspaceId,
        UUID userId,
        WorkspaceRole role,
        WorkspaceMembershipStatus status,
        Set<WorkspacePermissionScope> grantedScopes) {

    public WorkspaceMembership {
        Objects.requireNonNull(workspaceId, "Workspace Membership workspaceId must not be null");
        Objects.requireNonNull(userId, "Workspace Membership userId must not be null");
        Objects.requireNonNull(role, "Workspace Membership role must not be null");
        Objects.requireNonNull(status, "Workspace Membership status must not be null");
        Objects.requireNonNull(grantedScopes, "Workspace Membership grantedScopes must not be null");
        for (WorkspacePermissionScope scope : grantedScopes) {
            Objects.requireNonNull(scope, "Workspace Membership granted scope must not be null");
        }
        grantedScopes = Set.copyOf(grantedScopes);
    }

    /**
     * Whether this Membership allows Workspace-layer read access to the given
     * recognized permission scope for a User with the given account status.
     *
     * <p>This is only the Workspace-layer predicate. A positive result does
     * not bypass stricter rules or invariants of a concrete Project, Revision,
     * Ready-Made Product, Listing, or other individual domain operation, whose
     * own rules must additionally be satisfied.
     *
     * <p>Semantics (Workspace Membership APPROVED 1.0): the associated User
     * and the Membership must both be ACTIVE; ADMIN reads every current
     * recognized scope without stored grants; EDITOR and VIEWER read only
     * explicitly granted scopes; INVITED and SUSPENDED Memberships and
     * SUSPENDED or DEACTIVATED Users receive no ordinary access.
     */
    public boolean allowsWorkspaceLayerRead(UserStatus userStatus, WorkspacePermissionScope scope) {
        Objects.requireNonNull(userStatus, "userStatus must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (!hasOrdinaryAccessBasis(userStatus)) {
            return false;
        }
        if (role == WorkspaceRole.ADMIN) {
            return true;
        }
        return grantedScopes.contains(scope);
    }

    /**
     * Whether this Membership allows Workspace-layer write access to the given
     * recognized permission scope for a User with the given account status.
     *
     * <p>This is only the Workspace-layer predicate. A positive result does
     * not bypass stricter rules or invariants of a concrete Project, Revision,
     * Ready-Made Product, Listing, or other individual domain operation, whose
     * own rules must additionally be satisfied.
     *
     * <p>Semantics (Workspace Membership APPROVED 1.0): the associated User
     * and the Membership must both be ACTIVE; ADMIN writes every current
     * recognized scope without stored grants; EDITOR writes only explicitly
     * granted scopes; VIEWER never writes; INVITED and SUSPENDED Memberships
     * and SUSPENDED or DEACTIVATED Users receive no ordinary access.
     */
    public boolean allowsWorkspaceLayerWrite(UserStatus userStatus, WorkspacePermissionScope scope) {
        Objects.requireNonNull(userStatus, "userStatus must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (!hasOrdinaryAccessBasis(userStatus)) {
            return false;
        }
        return switch (role) {
            case ADMIN -> true;
            case EDITOR -> grantedScopes.contains(scope);
            case VIEWER -> false;
        };
    }

    private boolean hasOrdinaryAccessBasis(UserStatus userStatus) {
        return userStatus == UserStatus.ACTIVE && status == WorkspaceMembershipStatus.ACTIVE;
    }
}
