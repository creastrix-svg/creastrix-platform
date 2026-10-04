package com.creastrix.platform.platformaccess.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable reported assignment facts for the approved platform-access contract.
 *
 * <p>This is neither a persisted aggregate nor evidence that a grant is current,
 * unique, authenticated, or approved. Missing and malformed facts are retained
 * for an explicit policy denial rather than repaired by the constructor. No
 * lifecycle transition, clock lookup, audit write, or other side effect occurs.
 */
public record PlatformAccessGrant(
        UUID id,
        UUID recipientId,
        PlatformRole role,
        int catalogVersion,
        int bundleVersion,
        long revision,
        State state,
        Instant startsAt,
        Validity validity,
        Scope scope
) {

    public enum State {
        ACTIVE,
        SUSPENDED,
        REVOKED
    }

    public enum ValidityKind {
        BOUNDED,
        UNBOUNDED
    }

    /**
     * Explicit validity facts; null or inconsistent combinations do not mean
     * unbounded authority and must be rejected by the policy.
     */
    public record Validity(ValidityKind kind, Instant expiresAt) {
    }

    public enum ScopeKind {
        PLATFORM_SECURITY_METADATA,
        EXACT_USERS
    }

    /**
     * Snapshot of the reported resource bounds, without normalizing invalid data.
     *
     * <p>A defensive list copy preserves duplicates and null entries so a caller
     * cannot alter the snapshot and the policy can deny malformed scope facts.
     * Null collection references remain missing evidence, not empty scope.
     */
    public record Scope(ScopeKind kind, List<UUID> userIds) {

        public Scope {
            userIds = userIds == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(userIds));
        }
    }
}
