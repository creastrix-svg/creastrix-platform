package com.creastrix.platform.workspace.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.workspace.domain.Workspace;
import com.creastrix.platform.workspace.domain.WorkspaceMembership;

/**
 * Outbound application port for Workspace persistence.
 *
 * <p>Only the operations required by the Workspace application service exist.
 * There are deliberately no Membership mutation, deletion, ownership transfer,
 * generic CRUD, or paging operations.
 *
 * <p>No implementation detail (SQL, JDBC, Spring types) belongs to this
 * contract.
 */
public interface WorkspaceRepository {

    /**
     * Persists a User-owned Workspace together with its owner's initial ACTIVE
     * ADMIN Workspace Membership.
     *
     * <p>This operation does not own a transaction boundary. It must run
     * inside the existing WorkspaceService creation transaction, because the
     * Workspace and its initial Membership have to commit atomically.
     */
    void createUserOwned(UUID workspaceId, UUID ownerUserId);

    /**
     * Persists an Organization-owned Workspace together with its creator's
     * initial ACTIVE ADMIN Workspace Membership.
     *
     * <p>This operation does not own a transaction boundary. It must run
     * inside the existing WorkspaceService creation transaction, because the
     * Workspace and its initial Membership have to commit atomically.
     */
    void createOrganizationOwned(UUID workspaceId, UUID owningOrganizationId, UUID creatorUserId);

    Optional<Workspace> findById(UUID workspaceId);

    List<WorkspaceMembership> findMembershipsByWorkspaceId(UUID workspaceId);
}
