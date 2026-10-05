package com.creastrix.platform.platformaccess.application;

import java.util.UUID;

import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Assurance;

/**
 * Trusted, process-local session/assurance view for an explicitly constructed workflow.
 *
 * <p>No browser DTO, claim map, provider callback or request-supplied Actor becomes
 * authority through this port. The adapter must bind the initiating identity and
 * return its current session generation and previously established assurance, not
 * refresh idle activity merely because admission is being attempted. Implementations
 * must be bounded, nonblocking and perform no network or diagnostic output: refresh
 * occurs after database waits while the workflow still holds its authority locks.
 *
 * <p>This unwired slice has synthetic test adapters only. It neither verifies real
 * MFA/session ownership nor solves the separate browser-cookie follow-up. Persisted
 * account status/generation and grant facts always come from the locked repository,
 * never from this adapter. Exact assurance nanoseconds are preserved for I1 policy.
 */
@FunctionalInterface
public interface TrustedPlatformAccessFacts {
    Snapshot current();

    record Snapshot(UUID userId, UUID sessionGeneration, Assurance assurance) { }
}
