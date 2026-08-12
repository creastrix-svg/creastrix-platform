package com.creastrix.platform.organization.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.organization.domain.Organization;
import com.creastrix.platform.organization.domain.OrganizationMembership;

/**
 * Outbound application port for Organization persistence.
 *
 * <p>Only the operations required by the Organization application service
 * exist. There are deliberately no delete, role/status mutation, generic CRUD,
 * paging or Workspace operations.
 *
 * <p>No implementation detail (SQL, JDBC, Spring types) belongs to this
 * contract.
 */
public interface OrganizationRepository {

    /**
     * Persists an Organization together with its initial ACTIVE OWNER
     * Organization Membership for the creator.
     *
     * <p>This operation does not own a transaction boundary. It must run inside
     * the existing OrganizationService creation transaction, because the
     * Organization and its initial Membership have to commit atomically.
     */
    void create(UUID organizationId, UUID creatorUserId);

    Optional<Organization> findById(UUID organizationId);

    List<OrganizationMembership> findMembershipsByOrganizationId(UUID organizationId);
}
