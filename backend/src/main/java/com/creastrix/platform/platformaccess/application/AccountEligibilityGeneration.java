package com.creastrix.platform.platformaccess.application;

import java.util.UUID;

/**
 * Version-one, lossless carrier for a positive per-User eligibility counter.
 *
 * <p>The UUID is an opaque technical value, not a UUIDv4 identity, credential or
 * globally unique generation. Different Users can have equal counters; the
 * unchanged policy stamp separately binds the User, session, grant and revision.
 * This adapter neither issues generations nor establishes current authority.
 * Only committed authoritative database state may supply an accepted counter.
 */
public final class AccountEligibilityGeneration {

    private static final long VERSION_ONE_TAG = 0x435258454c494731L;

    private AccountEligibilityGeneration() {
    }

    public static UUID encode(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Account eligibility generation must be positive.");
        }
        return new UUID(VERSION_ONE_TAG, generation);
    }

    public static long decode(UUID value) {
        if (value == null || value.getMostSignificantBits() != VERSION_ONE_TAG
                || value.getLeastSignificantBits() <= 0) {
            throw new IllegalArgumentException("Unsupported account eligibility generation encoding.");
        }
        return value.getLeastSignificantBits();
    }
}
