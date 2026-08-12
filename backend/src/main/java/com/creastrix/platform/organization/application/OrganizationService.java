package com.creastrix.platform.organization.application;

import java.util.List;
import java.util.UUID;

import com.creastrix.platform.organization.application.port.OrganizationRepository;
import com.creastrix.platform.organization.domain.Organization;
import com.creastrix.platform.organization.domain.OrganizationCreatorNotActiveException;
import com.creastrix.platform.organization.domain.OrganizationMembership;
import com.creastrix.platform.organization.domain.OrganizationNotFoundException;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Organization foundation.
 *
 * <p>The creator is verified through the User application layer: an intentional
 * application-to-application dependency inside the modular monolith. This
 * service never touches User persistence directly.
 *
 * <p>Authorization boundary: this slice proves that the creator User exists and
 * is ACTIVE. Authentication and caller identity proof are not implemented, so
 * this service does not prove that the caller actually is the creator. General
 * Organization authority and future delegation rules remain outside this
 * slice's scope.
 */
@Service
public class OrganizationService {

    private final UserService userService;
    private final OrganizationRepository organizations;

    public OrganizationService(UserService userService, OrganizationRepository organizations) {
        this.userService = userService;
        this.organizations = organizations;
    }

    /**
     * Creates an Organization together with its creator's initial ACTIVE OWNER
     * Organization Membership in one transaction.
     *
     * @throws com.creastrix.platform.user.domain.UserNotFoundException
     *         if the creator User does not exist
     * @throws OrganizationCreatorNotActiveException
     *         if the creator User is not ACTIVE
     */
    @Transactional
    public Organization createOrganization(UUID creatorUserId) {
        User creator = userService.findUser(creatorUserId);
        if (creator.status() != UserStatus.ACTIVE) {
            throw new OrganizationCreatorNotActiveException(creator.id(), creator.status());
        }
        UUID id = UUID.randomUUID();
        organizations.create(id, creator.id());
        return organizations.findById(id).orElseThrow(() -> new OrganizationNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Organization findOrganization(UUID organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException(organizationId));
    }

    @Transactional(readOnly = true)
    public List<OrganizationMembership> findMemberships(UUID organizationId) {
        return organizations.findMembershipsByOrganizationId(organizationId);
    }
}
