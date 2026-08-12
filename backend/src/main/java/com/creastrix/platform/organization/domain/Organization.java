package com.creastrix.platform.organization.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An Organization: a shared business identity with a stable platform identity.
 *
 * <p>This slice models only the stable UUID identity. Business metadata such as
 * name or legal form is not defined by the approved specification and therefore
 * does not belong to this type.
 */
public record Organization(UUID id) {

    public Organization {
        Objects.requireNonNull(id, "Organization id must not be null");
    }
}
