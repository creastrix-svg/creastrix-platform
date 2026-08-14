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
 * <p>This slice implements the structural foundation and creation only, so no
 * lifecycle transition rule is expressed here yet.
 */
public enum ReadyMadeProductStatus {
    ACTIVE,
    ARCHIVED
}
