package com.creastrix.platform.workspace.persistence;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.workspace.application.port.WorkspaceRepository;
import com.creastrix.platform.workspace.domain.Workspace;
import com.creastrix.platform.workspace.domain.WorkspaceMembership;
import com.creastrix.platform.workspace.domain.WorkspaceMembershipStatus;
import com.creastrix.platform.workspace.domain.WorkspaceOwnerType;
import com.creastrix.platform.workspace.domain.WorkspacePermissionScope;
import com.creastrix.platform.workspace.domain.WorkspaceRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL persistence for Workspace, Workspace Membership, and the
 * Membership's granted permission scopes.
 *
 * <p>Only the operations required by this slice exist. There are deliberately
 * no delete or mutation operations and no generic mapping infrastructure.
 * Initial ADMIN Memberships store no scope rows: ADMIN's current-scope access
 * comes from its role, not from stored grants.
 */
@Repository
public class JdbcWorkspaceRepository implements WorkspaceRepository {

    private static final RowMapper<Workspace> WORKSPACE_ROW_MAPPER = (rs, rowNum) -> {
        WorkspaceOwnerType ownerType = WorkspaceOwnerType.valueOf(rs.getString("owner_type"));
        UUID ownerId = ownerType == WorkspaceOwnerType.USER
                ? rs.getObject("owner_user_id", UUID.class)
                : rs.getObject("owner_organization_id", UUID.class);
        return new Workspace(rs.getObject("id", UUID.class), ownerType, ownerId);
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts a USER-owned Workspace and its owner's initial ACTIVE ADMIN
     * Workspace Membership.
     *
     * <p>No transaction is started here: this runs inside the calling
     * application service transaction, so both rows commit atomically and the
     * deferred structural foundation invariant is satisfied at commit.
     */
    @Override
    public void createUserOwned(UUID workspaceId, UUID ownerUserId) {
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                        + "VALUES (?, 'USER', ?, NULL)",
                workspaceId, ownerUserId);
        insertInitialActiveAdminMembership(workspaceId, ownerUserId);
    }

    /**
     * Inserts an ORGANIZATION-owned Workspace and its creator's initial ACTIVE
     * ADMIN Workspace Membership.
     *
     * <p>No transaction is started here: this runs inside the calling
     * application service transaction, so both rows commit atomically and the
     * deferred structural foundation invariant is satisfied at commit.
     */
    @Override
    public void createOrganizationOwned(UUID workspaceId, UUID owningOrganizationId, UUID creatorUserId) {
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                        + "VALUES (?, 'ORGANIZATION', NULL, ?)",
                workspaceId, owningOrganizationId);
        insertInitialActiveAdminMembership(workspaceId, creatorUserId);
    }

    private void insertInitialActiveAdminMembership(UUID workspaceId, UUID userId) {
        // ADMIN and ACTIVE are written explicitly; no database defaults exist.
        // No scope rows are written for the initial ADMIN Membership.
        jdbcTemplate.update(
                "INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                        + "VALUES (?, ?, 'ADMIN', 'ACTIVE')",
                workspaceId, userId);
    }

    @Override
    public Optional<Workspace> findById(UUID workspaceId) {
        return jdbcTemplate
                .query("SELECT id, owner_type, owner_user_id, owner_organization_id "
                                + "FROM workspaces WHERE id = ?",
                        WORKSPACE_ROW_MAPPER, workspaceId)
                .stream()
                .findFirst();
    }

    /**
     * Reads roles, statuses and explicit grants from the same statement snapshot
     * at READ COMMITTED. The outer join retains Memberships without grants.
     */
    @Override
    public List<WorkspaceMembership> findMembershipsByWorkspaceId(UUID workspaceId) {
        return jdbcTemplate.query("""
                SELECT m.user_id, m.role, m.status, s.scope
                FROM workspace_memberships m
                LEFT JOIN workspace_membership_scopes s
                    ON s.workspace_id = m.workspace_id AND s.user_id = m.user_id
                WHERE m.workspace_id = ?
                ORDER BY m.user_id
                """, rs -> {
            List<WorkspaceMembership> memberships = new ArrayList<>();
            boolean hasRow = rs.next();
            while (hasRow) {
                UUID userId = rs.getObject("user_id", UUID.class);
                WorkspaceRole role = WorkspaceRole.valueOf(rs.getString("role"));
                WorkspaceMembershipStatus status = WorkspaceMembershipStatus.valueOf(rs.getString("status"));
                var scopes = EnumSet.noneOf(WorkspacePermissionScope.class);
                do {
                    String scope = rs.getString("scope");
                    if (scope != null) {
                        scopes.add(WorkspacePermissionScope.valueOf(scope));
                    }
                    hasRow = rs.next();
                } while (hasRow && userId.equals(rs.getObject("user_id", UUID.class)));
                memberships.add(new WorkspaceMembership(workspaceId, userId, role, status, scopes));
            }
            return memberships;
        }, workspaceId);
    }
}
