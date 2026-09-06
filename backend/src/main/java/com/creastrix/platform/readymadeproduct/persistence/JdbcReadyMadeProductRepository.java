package com.creastrix.platform.readymadeproduct.persistence;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.creastrix.platform.readymadeproduct.application.port.ReadyMadeProductRepository;
import com.creastrix.platform.readymadeproduct.domain.InvalidReadyMadeProductStatusTransitionException;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaCommandState;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaRejectionReason;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaResult;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductActorNotActiveException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductActorNotAuthorizedException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductManualQuantityDeltaAccessException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductManualQuantityDeltaNotRegisteredException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductManualQuantityDeltaPayloadMismatchException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductManualQuantityDeltaRejectedException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductNotFoundException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserNotFoundException;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.domain.WorkspaceMembership;
import com.creastrix.platform.workspace.domain.WorkspaceMembershipStatus;
import com.creastrix.platform.workspace.domain.WorkspacePermissionScope;
import com.creastrix.platform.workspace.domain.WorkspaceRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL persistence for the Ready-Made Product foundation.
 *
 * <p>Only the operations required by the implemented foundation exist. There
 * are deliberately no generic quantity mutation operations, no deletion, and
 * no generic mapping infrastructure. Every creation value, including the
 * initial ACTIVE lifecycle state, is written explicitly: the database has no
 * defaults and never generates the identity.
 *
 * <p>The supported lifecycle path uses the canonical lock order Membership,
 * exact READY_MADE_PRODUCTS scope for an ACTIVE EDITOR, User, Product. Manual
 * quantity commands extend the same order with the exact command row or insert
 * conflict only after Product. Future scope mutation must not acquire scope
 * before Membership. Global deadlock freedom is not claimed; a future
 * transaction-level retry policy must retry the whole transaction after
 * SQLSTATE 40P01.
 */
@Repository
public class JdbcReadyMadeProductRepository implements ReadyMadeProductRepository {

    private static final String MANUAL_DELTA_TABLE =
            "ready_made_product_manual_quantity_delta_commands";

    private static final RowMapper<ReadyMadeProduct> READY_MADE_PRODUCT_ROW_MAPPER =
            (rs, rowNum) -> new ReadyMadeProduct(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getObject("created_by_user_id", UUID.class),
                    ReadyMadeProductStatus.valueOf(rs.getString("status")),
                    rs.getLong("available_quantity"));

    private static final RowMapper<ManualQuantityDeltaResult> MANUAL_DELTA_ROW_MAPPER =
            (rs, rowNum) -> new ManualQuantityDeltaResult(
                    rs.getObject("product_id", UUID.class),
                    rs.getObject("command_id", UUID.class),
                    rs.getLong("delta"),
                    ManualQuantityDeltaCommandState.valueOf(rs.getString("state")),
                    nullableLong(rs.getObject("resulting_available_quantity")),
                    rs.getString("rejection_reason") == null
                            ? null
                            : ManualQuantityDeltaRejectionReason.valueOf(
                                    rs.getString("rejection_reason")),
                    nullableLong(rs.getObject("observed_available_quantity")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcReadyMadeProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts exactly one new Ready-Made Product row.
     *
     * <p>No transaction is started here: this runs inside the calling
     * application service transaction, so the deferred database creation gate
     * validates the row against committed state at that transaction's commit.
     */
    @Override
    public void create(
            UUID readyMadeProductId,
            UUID workspaceId,
            UUID createdByUserId,
            long availableQuantity) {
        jdbcTemplate.update(
                "INSERT INTO ready_made_products "
                        + "(id, workspace_id, created_by_user_id, status, available_quantity) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?)",
                readyMadeProductId, workspaceId, createdByUserId, availableQuantity);
    }

    @Override
    public Optional<ReadyMadeProduct> findById(UUID readyMadeProductId) {
        return jdbcTemplate
                .query("SELECT id, workspace_id, created_by_user_id, status, available_quantity "
                                + "FROM ready_made_products WHERE id = ?",
                        READY_MADE_PRODUCT_ROW_MAPPER, readyMadeProductId)
                .stream()
                .findFirst();
    }

    /**
     * Revalidates represented-actor authorization and lifecycle state under
     * locks. The surrounding application service transaction owns all locks.
     * Raw SQL clients are constrained structurally by V7 but are not proven to
     * represent an authorized actor.
     */
    @Override
    public ReadyMadeProduct transitionStatus(
            UUID readyMadeProductId,
            UUID actorUserId,
            ReadyMadeProductStatus expectedStatus,
            ReadyMadeProductStatus targetStatus) {
        ReadyMadeProduct observed = findById(readyMadeProductId)
                .orElseThrow(() -> new ReadyMadeProductNotFoundException(readyMadeProductId));

        Optional<MembershipLock> membershipLock = jdbcTemplate.query(
                        "SELECT role, status FROM workspace_memberships "
                                + "WHERE workspace_id = ? AND user_id = ? FOR UPDATE",
                        (rs, rowNum) -> new MembershipLock(
                                WorkspaceRole.valueOf(rs.getString("role")),
                                WorkspaceMembershipStatus.valueOf(rs.getString("status"))),
                        observed.workspaceId(), actorUserId)
                .stream()
                .findFirst();

        boolean exactScopeGranted = false;
        if (membershipLock.isPresent()
                && membershipLock.get().role() == WorkspaceRole.EDITOR
                && membershipLock.get().status() == WorkspaceMembershipStatus.ACTIVE) {
            exactScopeGranted = !jdbcTemplate.queryForList(
                    "SELECT scope FROM workspace_membership_scopes "
                            + "WHERE workspace_id = ? AND user_id = ? "
                            + "AND scope = 'READY_MADE_PRODUCTS' FOR UPDATE",
                    String.class, observed.workspaceId(), actorUserId).isEmpty();
        }

        Optional<User> actor = jdbcTemplate.query(
                        "SELECT id, status FROM users WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> new User(
                                rs.getObject("id", UUID.class),
                                UserStatus.valueOf(rs.getString("status"))),
                        actorUserId)
                .stream()
                .findFirst();

        ReadyMadeProduct locked = jdbcTemplate.query(
                        "SELECT id, workspace_id, created_by_user_id, status, available_quantity "
                                + "FROM ready_made_products WHERE id = ? FOR UPDATE",
                        READY_MADE_PRODUCT_ROW_MAPPER, readyMadeProductId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ReadyMadeProductNotFoundException(readyMadeProductId));

        User lockedActor = actor.orElseThrow(() -> new UserNotFoundException(actorUserId));
        if (lockedActor.status() != UserStatus.ACTIVE) {
            throw new ReadyMadeProductActorNotActiveException(
                    lockedActor.id(), lockedActor.status());
        }

        Set<WorkspacePermissionScope> lockedScopes = exactScopeGranted
                ? Set.of(WorkspacePermissionScope.READY_MADE_PRODUCTS)
                : Set.of();
        WorkspaceMembership lockedMembership = membershipLock
                .map(value -> new WorkspaceMembership(
                        locked.workspaceId(),
                        lockedActor.id(),
                        value.role(),
                        value.status(),
                        lockedScopes))
                .orElse(null);
        if (lockedMembership == null || !lockedMembership.allowsWorkspaceLayerWrite(
                lockedActor.status(), WorkspacePermissionScope.READY_MADE_PRODUCTS)) {
            throw new ReadyMadeProductActorNotAuthorizedException(
                    locked.id(), locked.workspaceId(), lockedActor.id());
        }

        if (locked.status() != expectedStatus) {
            throw new InvalidReadyMadeProductStatusTransitionException(
                    locked.id(), locked.status(), targetStatus);
        }
        ReadyMadeProduct transitioned = locked.transitionTo(targetStatus);
        int updated = jdbcTemplate.update(
                "UPDATE ready_made_products SET status = ? WHERE id = ? AND status = ?",
                transitioned.status().name(), transitioned.id(), expectedStatus.name());
        if (updated != 1) {
            throw new InvalidReadyMadeProductStatusTransitionException(
                    locked.id(), locked.status(), targetStatus);
        }
        return findById(transitioned.id())
                .orElseThrow(() -> new ReadyMadeProductNotFoundException(transitioned.id()));
    }

    @Override
    public ManualQuantityDeltaResult registerManualQuantityDelta(
            UUID readyMadeProductId,
            UUID commandId,
            long delta,
            UUID actorUserId) {
        lockAuthorizedProductForManualDelta(readyMadeProductId, actorUserId);

        int inserted = jdbcTemplate.update(
                "INSERT INTO " + MANUAL_DELTA_TABLE + " "
                        + "(product_id, command_id, delta, state, "
                        + "resulting_available_quantity, rejection_reason, "
                        + "observed_available_quantity) "
                        + "VALUES (?, ?, ?, 'REGISTERED', NULL, NULL, NULL) "
                        + "ON CONFLICT (product_id, command_id) DO NOTHING",
                readyMadeProductId, commandId, delta);
        if (inserted == 1) {
            return ManualQuantityDeltaResult.registered(
                    readyMadeProductId, commandId, delta);
        }

        ManualQuantityDeltaResult existing = lockManualDelta(readyMadeProductId, commandId)
                .orElseThrow(() -> new IllegalStateException(
                        "Conflicting manual quantity-delta registration disappeared"));
        requireMatchingDelta(existing, delta);
        return existing;
    }

    @Override
    public ManualQuantityDeltaResult applyManualQuantityDelta(
            UUID readyMadeProductId,
            UUID commandId,
            long delta,
            UUID actorUserId) {
        ReadyMadeProduct product =
                lockAuthorizedProductForManualDelta(readyMadeProductId, actorUserId);
        ManualQuantityDeltaResult command = lockManualDelta(readyMadeProductId, commandId)
                .orElseThrow(ReadyMadeProductManualQuantityDeltaNotRegisteredException::new);
        requireMatchingDelta(command, delta);
        if (command.state() != ManualQuantityDeltaCommandState.REGISTERED) {
            return command;
        }

        try {
            ReadyMadeProduct changedProduct = product.applyManualQuantityDelta(delta);
            int productRows = jdbcTemplate.update(
                    "UPDATE ready_made_products SET available_quantity = ? WHERE id = ?",
                    changedProduct.availableQuantity(), changedProduct.id());
            if (productRows != 1) {
                throw new IllegalStateException(
                        "Locked Ready-Made Product was not updated exactly once");
            }
            int commandRows = jdbcTemplate.update(
                    "UPDATE " + MANUAL_DELTA_TABLE + " "
                            + "SET state = 'APPLIED', resulting_available_quantity = ?, "
                            + "rejection_reason = NULL, observed_available_quantity = NULL "
                            + "WHERE product_id = ? AND command_id = ? AND state = 'REGISTERED'",
                    changedProduct.availableQuantity(), readyMadeProductId, commandId);
            requireSingleTerminalTransition(commandRows);
            return ManualQuantityDeltaResult.applied(
                    readyMadeProductId, commandId, delta,
                    changedProduct.availableQuantity());
        } catch (ReadyMadeProductManualQuantityDeltaRejectedException rejection) {
            int commandRows = jdbcTemplate.update(
                    "UPDATE " + MANUAL_DELTA_TABLE + " "
                            + "SET state = 'REJECTED', resulting_available_quantity = NULL, "
                            + "rejection_reason = ?, observed_available_quantity = ? "
                            + "WHERE product_id = ? AND command_id = ? AND state = 'REGISTERED'",
                    rejection.reason().name(), rejection.observedAvailableQuantity(),
                    readyMadeProductId, commandId);
            requireSingleTerminalTransition(commandRows);
            return ManualQuantityDeltaResult.rejected(
                    readyMadeProductId, commandId, delta,
                    rejection.reason(), rejection.observedAvailableQuantity());
        }
    }

    /**
     * Reads Product only to resolve immutable Workspace identity, then locks
     * Membership, required exact scope, actor User, and Product. Every failure
     * is collapsed before command lookup or payload comparison.
     */
    private ReadyMadeProduct lockAuthorizedProductForManualDelta(
            UUID readyMadeProductId, UUID actorUserId) {
        ReadyMadeProduct observed = findById(readyMadeProductId)
                .orElseThrow(ReadyMadeProductManualQuantityDeltaAccessException::new);

        Optional<MembershipLock> membershipLock = jdbcTemplate.query(
                        "SELECT role, status FROM workspace_memberships "
                                + "WHERE workspace_id = ? AND user_id = ? FOR UPDATE",
                        (rs, rowNum) -> new MembershipLock(
                                WorkspaceRole.valueOf(rs.getString("role")),
                                WorkspaceMembershipStatus.valueOf(rs.getString("status"))),
                        observed.workspaceId(), actorUserId)
                .stream()
                .findFirst();

        boolean exactScopeGranted = false;
        if (membershipLock.isPresent()
                && membershipLock.get().role() == WorkspaceRole.EDITOR
                && membershipLock.get().status() == WorkspaceMembershipStatus.ACTIVE) {
            exactScopeGranted = !jdbcTemplate.queryForList(
                    "SELECT scope FROM workspace_membership_scopes "
                            + "WHERE workspace_id = ? AND user_id = ? "
                            + "AND scope = 'READY_MADE_PRODUCTS' FOR UPDATE",
                    String.class, observed.workspaceId(), actorUserId).isEmpty();
        }

        Optional<User> actor = jdbcTemplate.query(
                        "SELECT id, status FROM users WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> new User(
                                rs.getObject("id", UUID.class),
                                UserStatus.valueOf(rs.getString("status"))),
                        actorUserId)
                .stream()
                .findFirst();

        ReadyMadeProduct locked = jdbcTemplate.query(
                        "SELECT id, workspace_id, created_by_user_id, status, available_quantity "
                                + "FROM ready_made_products WHERE id = ? FOR UPDATE",
                        READY_MADE_PRODUCT_ROW_MAPPER, readyMadeProductId)
                .stream()
                .findFirst()
                .orElseThrow(ReadyMadeProductManualQuantityDeltaAccessException::new);

        if (actor.isEmpty() || actor.get().status() != UserStatus.ACTIVE) {
            throw new ReadyMadeProductManualQuantityDeltaAccessException();
        }
        Set<WorkspacePermissionScope> lockedScopes = exactScopeGranted
                ? Set.of(WorkspacePermissionScope.READY_MADE_PRODUCTS)
                : Set.of();
        WorkspaceMembership lockedMembership = membershipLock
                .map(value -> new WorkspaceMembership(
                        locked.workspaceId(),
                        actorUserId,
                        value.role(),
                        value.status(),
                        lockedScopes))
                .orElse(null);
        if (lockedMembership == null || !lockedMembership.allowsWorkspaceLayerWrite(
                actor.get().status(), WorkspacePermissionScope.READY_MADE_PRODUCTS)) {
            throw new ReadyMadeProductManualQuantityDeltaAccessException();
        }
        return locked;
    }

    private Optional<ManualQuantityDeltaResult> lockManualDelta(
            UUID readyMadeProductId, UUID commandId) {
        return jdbcTemplate.query(
                        "SELECT product_id, command_id, delta, state, "
                                + "resulting_available_quantity, rejection_reason, "
                                + "observed_available_quantity FROM " + MANUAL_DELTA_TABLE + " "
                                + "WHERE product_id = ? AND command_id = ? FOR UPDATE",
                        MANUAL_DELTA_ROW_MAPPER, readyMadeProductId, commandId)
                .stream()
                .findFirst();
    }

    private static void requireMatchingDelta(
            ManualQuantityDeltaResult command, long suppliedDelta) {
        if (command.delta() != suppliedDelta) {
            throw new ReadyMadeProductManualQuantityDeltaPayloadMismatchException();
        }
    }

    private static void requireSingleTerminalTransition(int commandRows) {
        if (commandRows != 1) {
            throw new IllegalStateException(
                    "Registered manual quantity-delta command did not transition exactly once");
        }
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private record MembershipLock(WorkspaceRole role, WorkspaceMembershipStatus status) {
    }
}
