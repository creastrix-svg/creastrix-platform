package com.creastrix.platform.platformaccess.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.creastrix.platform.platformaccess.application.OwnedPlatformAccessTransactions.Deadline;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.AuditEvent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Intent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Kind;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.OutcomeKind;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.RegisteredIntent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.StoredOutcome;
import com.creastrix.platform.platformaccess.application.port.PlatformAccessRepository;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.ReadReason;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.Scope;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.ScopeKind;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.State;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.Validity;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.ValidityKind;
import com.creastrix.platform.platformaccess.domain.PlatformRole;
import com.creastrix.platform.user.domain.UserStatus;

/**
 * Explicit, unwired JDBC adapter. It never opens/commits a connection or logs
 * under locks. Canonical locks are Users, grants, then the exact intent; all are
 * retained through the owner's physical outcome. PostgreSQL orders UUIDs here,
 * rather than Java's signed UUID comparison. No changed discovery grows the
 * lock set after lower-order locks have been taken.
 */
public final class JdbcPlatformAccessRepository implements PlatformAccessRepository {
    // PostgreSQL 18's exact finite timestamp interval, also representable by
    // Java Instant. Binding below avoids the driver's narrower infinity sentinel.
    private static final Instant MIN_PERSISTED_INSTANT = Instant.parse("-4713-11-24T00:00:00Z");
    private static final Instant END_PERSISTED_INSTANT = Instant.parse("+294277-01-01T00:00:00Z");

    @Override
    public Session session(Connection connection, Deadline deadline) throws SQLException {
        Objects.requireNonNull(connection, "An owned connection is required.");
        Objects.requireNonNull(deadline, "An owned deadline is required.");
        if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new SQLException("Platform workflow requires an owned READ COMMITTED transaction.", "25000");
        }
        return new JdbcSession(connection, deadline);
    }

    private static final class JdbcSession implements Session {
        private final Connection connection;
        private final Deadline deadline;
        private UUID actorId;
        private Intent requested;
        private UUID historicalId;
        private List<UUID> discoveredSlot;
        private UUID discoveredTargetRecipient;
        private Set<UUID> lockedUsers;

        private JdbcSession(Connection connection, Deadline deadline) {
            this.connection = connection;
            this.deadline = deadline;
        }

        @Override
        public Authority lockAuthority(UUID actor, Intent intent, boolean receipt) throws SQLException {
            if (requested != null) {
                throw new SQLException("Authority locks can be established only once.", "25000");
            }
            actorId = Objects.requireNonNull(actor, "Actor identity is required.");
            requested = Objects.requireNonNull(intent, "Exact intent is required.");
            historicalId = receipt ? intent.initiatorUserId() : actor;
            // Only immutable authority relationships are discovered here. Never
            // inspect an operation key to decide who may access its supplied target.
            discoveredSlot = slot(actor);
            discoveredTargetRecipient = intent.targetGrantId() == null ? null
                    : one("SELECT recipient_user_id FROM platform_access_grants WHERE id=?",
                            r -> r.getObject(1, UUID.class), intent.targetGrantId());
            // A stable recipient mismatch is a current resource denial, not changed
            // discovery. Retain the supplied target for the service gate after locks
            // and refresh; never adopt or add a User lock for the discovered recipient.
            Set<UUID> userIds = new HashSet<>();
            userIds.add(actor);
            userIds.add(intent.targetUserId());
            if (historicalId != null) { userIds.add(historicalId); }
            lockedUsers = new HashSet<>(query("SELECT id FROM users WHERE id IN (" + placeholders(userIds.size())
                    + ") ORDER BY id FOR NO KEY UPDATE", r -> r.getObject(1, UUID.class), userIds.toArray()));

            Set<UUID> grantIds = new HashSet<>(discoveredSlot);
            if (discoveredTargetRecipient != null) { grantIds.add(intent.targetGrantId()); }
            if (!grantIds.isEmpty()) {
                List<UUID> actualLocks = query("SELECT id FROM platform_access_grants WHERE id IN ("
                        + placeholders(grantIds.size()) + ") ORDER BY id FOR NO KEY UPDATE",
                        r -> r.getObject(1, UUID.class), grantIds.toArray());
                if (!new HashSet<>(actualLocks).equals(grantIds)) { throw new AuthorityChangedException(); }
            }
            return refreshAuthority();
        }

        @Override
        public Authority refreshAuthority() throws SQLException {
            if (lockedUsers == null) { throw new SQLException("Authority lock phase is required.", "25000"); }
            List<UUID> currentSlot = slot(actorId);
            if (!currentSlot.equals(discoveredSlot)) { throw new AuthorityChangedException(); }
            Map<UUID, UserFact> users = new HashMap<>();
            if (!lockedUsers.isEmpty()) {
                for (UserFact user : query("SELECT id,status,account_eligibility_generation FROM users WHERE id IN ("
                        + placeholders(lockedUsers.size()) + ") ORDER BY id", r -> new UserFact(
                                r.getObject("id", UUID.class), UserStatus.valueOf(r.getString("status")),
                                r.getLong("account_eligibility_generation")), lockedUsers.toArray())) {
                    users.put(user.id(), user);
                }
            }
            if (!users.keySet().equals(lockedUsers)) { throw new AuthorityChangedException(); }
            PlatformAccessGrant actorGrant = currentSlot.isEmpty() ? null : grant(currentSlot.getFirst());
            PlatformAccessGrant targetGrant = requested.targetGrantId() == null ? null : grant(requested.targetGrantId());
            if (!Objects.equals(targetGrant == null ? null : targetGrant.recipientId(), discoveredTargetRecipient)) {
                throw new AuthorityChangedException();
            }
            Set<UUID> candidates = new HashSet<>(lockedUsers);
            if (actorGrant != null) { candidates.addAll(actorGrant.scope().userIds()); }
            if (targetGrant != null) { candidates.addAll(targetGrant.scope().userIds()); }
            Set<UUID> existing = candidates.isEmpty() ? Set.of() : new HashSet<>(query(
                    "SELECT id FROM users WHERE id IN (" + placeholders(candidates.size()) + ") ORDER BY id",
                    r -> r.getObject(1, UUID.class), candidates.toArray()));
            // Scope rows and their target FKs are retained. We do not lock up to
            // 100 unrelated Users merely to establish existence, nor expose them.
            return new Authority(users.get(actorId), users.get(requested.targetUserId()), users.get(historicalId),
                    actorGrant, targetGrant, currentSlot, slot(requested.targetUserId()), existing);
        }

        @Override
        public Instant now() throws SQLException {
            return one("SELECT clock_timestamp()", r -> instant(r, 1));
        }

        @Override
        public RegisteredIntent lockIntent(UUID initiatorId, UUID operationId) throws SQLException {
            return one("SELECT * FROM platform_access_operation_intents WHERE initiator_user_id=? AND operation_id=? "
                    + "FOR NO KEY UPDATE", r -> new RegisteredIntent(readIntent(r), instant(r, "registered_at"),
                            r.getObject("correlation_id", UUID.class), r.getObject("actor_grant_id", UUID.class),
                            r.getLong("actor_grant_revision")), initiatorId, operationId);
        }

        @Override
        public boolean insertIntentIfAbsent(RegisteredIntent registered) throws SQLException {
            Objects.requireNonNull(registered, "Registration is required.");
            Intent intent = registered.intent();
            if (intent == null || !intent.valid()) { throw new IllegalArgumentException("Exact intent is invalid."); }
            requirePersistedInstant(registered.registeredAt());
            return update("""
                    INSERT INTO platform_access_operation_intents(initiator_user_id,operation_id,intent_version,kind,
                        target_user_id,target_grant_id,read_reason,expected_revision,registered_at,correlation_id,
                        actor_grant_id,actor_grant_revision)
                    VALUES (?,?,1,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (initiator_user_id,operation_id) DO NOTHING
                    """, intent.initiatorUserId(), intent.operationId(), intent.kind(), intent.targetUserId(),
                    intent.targetGrantId(), intent.readReason(), intent.expectedRevision(), registered.registeredAt(),
                    registered.correlationId(), registered.actorGrantId(), registered.actorGrantRevision()) == 1;
        }

        @Override
        public StoredOutcome outcome(UUID initiatorId, UUID operationId) throws SQLException {
            return one("SELECT * FROM platform_access_operation_outcomes WHERE initiator_user_id=? AND operation_id=?",
                    r -> new StoredOutcome(OutcomeKind.valueOf(r.getString("outcome_kind")), instant(r, "admitted_at"),
                            r.getObject("actor_grant_id", UUID.class), r.getLong("actor_grant_revision"),
                            r.getObject("audit_event_id", UUID.class), state(r.getString("before_state")),
                            r.getObject("before_revision", Long.class), state(r.getString("after_state")),
                            r.getObject("after_revision", Long.class)), initiatorId, operationId);
        }

        @Override
        public void writeAudit(AuditEvent event) throws SQLException {
            Objects.requireNonNull(event, "A closed audit event is required.");
            requirePersistedInstant(event.at());
            update("""
                    INSERT INTO platform_access_audit_events(id,event_version,event_kind,event_at,correlation_id,attempt_id,
                        actor_user_id,actor_grant_id,actor_grant_revision,actor_role,catalog_version,bundle_version,
                        supplied_target_user_id,supplied_target_grant_id,target_verification,target_user_id,target_grant_id,
                        initiator_user_id,operation_id,action,permission,projection,reason,outcome,
                        before_state,before_revision,after_state,after_revision,linked_event_id)
                    VALUES (?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, event.id(), event.kind(), event.at(), event.correlationId(), event.attemptId(),
                    event.actorUserId(), event.actorGrantId(), event.actorGrantRevision(), event.actorRole(),
                    event.actorGrantId() == null ? null : 1, event.actorGrantId() == null ? null : 1,
                    event.suppliedTargetUserId(), event.suppliedTargetGrantId(), event.verification(), event.targetUserId(),
                    event.targetGrantId(), event.initiatorUserId(), event.operationId(), event.action(), event.permission(),
                    event.projection(), event.reason(), event.outcome(), event.beforeState(), event.beforeRevision(),
                    event.afterState(), event.afterRevision(), event.linkedEventId());
        }

        @Override
        public void writeOutcome(Intent intent, StoredOutcome outcome) throws SQLException {
            Objects.requireNonNull(intent, "Exact intent is required.");
            Objects.requireNonNull(outcome, "A closed outcome is required.");
            requirePersistedInstant(outcome.admittedAt());
            update("""
                    INSERT INTO platform_access_operation_outcomes(initiator_user_id,operation_id,outcome_kind,
                        admitted_at,actor_grant_id,actor_grant_revision,audit_event_id,
                        before_state,before_revision,after_state,after_revision)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, intent.initiatorUserId(), intent.operationId(), outcome.kind(), outcome.admittedAt(),
                    outcome.actorGrantId(), outcome.actorGrantRevision(), outcome.auditEventId(), outcome.beforeState(),
                    outcome.beforeRevision(), outcome.afterState(), outcome.afterRevision());
        }

        @Override
        public int revoke(Intent intent, UUID actor, Instant at) throws SQLException {
            if (intent == null || !intent.valid() || intent.kind() != Kind.SUPPORT_REVOKE
                    || intent.expectedRevision() == Long.MAX_VALUE) {
                throw new IllegalArgumentException("Exact revoke intent is invalid.");
            }
            requirePersistedInstant(at);
            return update("""
                    UPDATE platform_access_grants SET state='REVOKED',revision=revision+1,
                        last_changed_by_user_id=?,last_changed_at=?,revoked_by_user_id=?,revoked_at=?
                    WHERE id=? AND recipient_user_id=? AND revision=? AND state IN ('ACTIVE','SUSPENDED')
                        AND role='SUPPORT_READ'
                    """, actor, at, actor, at, intent.targetGrantId(), intent.targetUserId(), intent.expectedRevision());
        }

        private List<UUID> slot(UUID userId) throws SQLException {
            return query("SELECT id FROM platform_access_grants WHERE recipient_user_id=? AND state<>'REVOKED' ORDER BY id",
                    r -> r.getObject(1, UUID.class), userId);
        }

        private PlatformAccessGrant grant(UUID id) throws SQLException {
            PlatformAccessGrant stored = one("SELECT * FROM platform_access_grants WHERE id=?", r ->
                    new PlatformAccessGrant(r.getObject("id", UUID.class), r.getObject("recipient_user_id", UUID.class),
                            PlatformRole.valueOf(r.getString("role")), r.getInt("catalog_version"), r.getInt("bundle_version"),
                            r.getLong("revision"), State.valueOf(r.getString("state")), instant(r, "starts_at"),
                            new Validity(ValidityKind.valueOf(r.getString("validity_kind")), instant(r, "expires_at")),
                            new Scope(ScopeKind.valueOf(r.getString("scope_kind")), List.of())), id);
            if (stored == null) { return null; }
            List<UUID> targets = query("SELECT target_user_id FROM platform_access_grant_targets WHERE grant_id=? ORDER BY target_user_id",
                    r -> r.getObject(1, UUID.class), id);
            return new PlatformAccessGrant(stored.id(), stored.recipientId(), stored.role(), stored.catalogVersion(),
                    stored.bundleVersion(), stored.revision(), stored.state(), stored.startsAt(), stored.validity(),
                    new Scope(stored.scope().kind(), targets));
        }

        private int update(String sql, Object... arguments) throws SQLException {
            try (PreparedStatement statement = prepare(sql, arguments)) { return statement.executeUpdate(); }
        }

        private <T> T one(String sql, Row<T> mapper, Object... arguments) throws SQLException {
            List<T> results = query(sql, mapper, arguments);
            if (results.size() > 1) { throw new SQLException("Exact lookup returned more than one row.", "PA002"); }
            return results.isEmpty() ? null : results.getFirst();
        }

        private <T> List<T> query(String sql, Row<T> mapper, Object... arguments) throws SQLException {
            try (PreparedStatement statement = prepare(sql, arguments); ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) { result.add(mapper.map(rows)); }
                return result;
            }
        }

        private PreparedStatement prepare(String sql, Object... arguments) throws SQLException {
            // Validate before driver binding; JDBC/database coercion must never
            // discard nanos or turn finite application inputs into infinity.
            for (Object argument : arguments) {
                if (argument instanceof Instant instant) { requirePersistedInstant(instant); }
            }
            deadline.configure(connection);
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                deadline.beforeStatement(statement);
                for (int index = 0; index < arguments.length; index++) {
                    Object value = arguments[index];
                    if (value instanceof Instant instant) {
                        // Unknown-typed bound text is converted by PostgreSQL in
                        // the timestamp column's context, not interpolated SQL.
                        // OffsetDateTime binding would turn the earliest finite
                        // PostgreSQL dates into the driver's -infinity sentinel.
                        statement.setObject(index + 1, timestampText(instant), Types.OTHER);
                    } else {
                        if (value instanceof Enum<?> enumeration) { value = enumeration.name(); }
                        statement.setObject(index + 1, value);
                    }
                }
                return statement;
            } catch (SQLException | RuntimeException failure) {
                try { statement.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
    }

    private static Intent readIntent(ResultSet row) throws SQLException {
        if (row.getInt("intent_version") != 1) { throw new SQLException("Unsupported stored intent version.", "PA003"); }
        String reason = row.getString("read_reason");
        return new Intent(row.getObject("initiator_user_id", UUID.class), row.getObject("operation_id", UUID.class),
                Kind.valueOf(row.getString("kind")), row.getObject("target_user_id", UUID.class),
                row.getObject("target_grant_id", UUID.class), reason == null ? null : ReadReason.valueOf(reason),
                row.getObject("expected_revision", Long.class));
    }

    private static State state(String value) { return value == null ? null : State.valueOf(value); }
    private static Instant instant(ResultSet rows, String name) throws SQLException {
        OffsetDateTime value = rows.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static Instant instant(ResultSet rows, int column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class).toInstant();
    }
    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private static void requirePersistedInstant(Instant instant) {
        if (instant == null || instant.getNano() % 1000 != 0 || instant.isBefore(MIN_PERSISTED_INSTANT)
                || !instant.isBefore(END_PERSISTED_INSTANT)) {
            throw new IllegalArgumentException("Persisted timestamp requires finite exact microseconds.");
        }
    }
    private static String timestampText(Instant instant) {
        LocalDateTime value = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        int year = value.getYear();
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d.%06d+00%s",
                year <= 0 ? 1 - year : year, value.getMonthValue(), value.getDayOfMonth(),
                value.getHour(), value.getMinute(), value.getSecond(), value.getNano() / 1000,
                year <= 0 ? " BC" : "");
    }
    @FunctionalInterface
    private interface Row<T> { T map(ResultSet row) throws SQLException; }
}
