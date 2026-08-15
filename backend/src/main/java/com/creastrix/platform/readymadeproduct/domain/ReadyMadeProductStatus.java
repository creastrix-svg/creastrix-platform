package com.creastrix.platform.readymadeproduct.domain;

/**
 * The lifecycle state of a {@link ReadyMadeProduct}.
 *
 * <p>ACTIVE and ARCHIVED are exactly the lifecycle states recognized by the
 * approved specification (Ready-Made Product APPROVED 1.0). There is no
 * PUBLISHED and no DELETED state in MVP.
 *
 * <p>Lifecycle is independent from available quantity: an ACTIVE product may
 * have quantity zero and an ARCHIVED product may retain quantity greater than
 * zero.
 *
 * <p>The supported lifecycle is deliberately closed: ACTIVE may transition
 * only to ARCHIVED, and ARCHIVED may transition only to ACTIVE. A same-state
 * command is not a lifecycle transition.
 */
public enum ReadyMadeProductStatus {
    ACTIVE,
    ARCHIVED;

    /**
     * The single canonical rule answering whether {@code this -> target} is a
     * supported lifecycle transition.
     */
    public boolean canTransitionTo(ReadyMadeProductStatus target) {
        return switch (this) {
            case ACTIVE -> target == ARCHIVED;
            case ARCHIVED -> target == ACTIVE;
        };
    }
}
