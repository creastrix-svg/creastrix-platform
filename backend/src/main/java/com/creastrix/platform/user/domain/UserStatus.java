package com.creastrix.platform.user.domain;

import java.util.Set;

/**
 * Account and access status of a {@link User}.
 *
 * <p>Defined by the approved User specification. This is account and access
 * state only: it is not deletion, membership, profile, or workspace state.
 */
public enum UserStatus {

    ACTIVE,
    SUSPENDED,
    DEACTIVATED;

    /**
     * The single canonical rule answering whether {@code this -> target} is a
     * supported lifecycle transition.
     *
     * <p>A same-state command is not a lifecycle transition and is not
     * supported. Ordinary reactivation from {@code DEACTIVATED} is not
     * supported.
     */
    public boolean canTransitionTo(UserStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<UserStatus> allowedTargets() {
        return switch (this) {
            case ACTIVE -> Set.of(SUSPENDED, DEACTIVATED);
            case SUSPENDED -> Set.of(ACTIVE, DEACTIVATED);
            case DEACTIVATED -> Set.of();
        };
    }
}
