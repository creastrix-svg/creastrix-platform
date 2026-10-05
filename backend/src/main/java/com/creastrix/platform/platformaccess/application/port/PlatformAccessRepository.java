package com.creastrix.platform.platformaccess.application.port;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.creastrix.platform.platformaccess.application.OwnedPlatformAccessTransactions.Deadline;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.AuditEvent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Intent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.RegisteredIntent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.StoredOutcome;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant;
import com.creastrix.platform.user.domain.UserStatus;

/**
 * Narrow storage port over an already owned physical transaction. No operation
 * grants authority, commits, leases a second connection or discloses raw records
 * externally. The service gates the supplied target before every intent lookup.
 */
public interface PlatformAccessRepository {
    Session session(Connection connection, Deadline deadline) throws SQLException;

    record UserFact(UUID id, UserStatus status, long generation) { }
    record Authority(UserFact actor, UserFact target, UserFact historicalInitiator,
                     PlatformAccessGrant actorGrant, PlatformAccessGrant targetGrant,
                     List<UUID> slot, List<UUID> targetSlot, Set<UUID> existingUsers) {
        public Authority {
            slot = List.copyOf(slot);
            targetSlot = List.copyOf(targetSlot);
            existingUsers = Set.copyOf(existingUsers);
        }
    }

    interface Session {
        Authority lockAuthority(UUID actorId, Intent intent, boolean receipt) throws SQLException;
        Authority refreshAuthority() throws SQLException;
        /** Separate post-wait database command; never transaction-start now(). */
        Instant now() throws SQLException;
        RegisteredIntent lockIntent(UUID initiatorId, UUID operationId) throws SQLException;
        boolean insertIntentIfAbsent(RegisteredIntent intent) throws SQLException;
        StoredOutcome outcome(UUID initiatorId, UUID operationId) throws SQLException;
        void writeAudit(AuditEvent event) throws SQLException;
        void writeOutcome(Intent intent, StoredOutcome outcome) throws SQLException;
        int revoke(Intent intent, UUID actorId, Instant at) throws SQLException;
    }

    /** Safe internal fail-closed signal; it carries no conflicting IDs or stored intent. */
    final class AuthorityChangedException extends SQLException {
        public AuthorityChangedException() {
            super("Platform authority discovery changed before locking.", "PA001");
        }
    }
}
