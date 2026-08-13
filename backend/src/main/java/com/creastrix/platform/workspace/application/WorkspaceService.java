package com.creastrix.platform.workspace.application;

import java.util.List;
import java.util.UUID;

import com.creastrix.platform.organization.application.OrganizationService;
import com.creastrix.platform.organization.domain.Organization;
import com.creastrix.platform.organization.domain.OrganizationMembershipStatus;
import com.creastrix.platform.organization.domain.OrganizationRole;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.application.port.WorkspaceRepository;
import com.creastrix.platform.workspace.domain.Workspace;
import com.creastrix.platform.workspace.domain.WorkspaceCreatorNotActiveException;
import com.creastrix.platform.workspace.domain.WorkspaceCreatorNotOrganizationOwnerException;
import com.creastrix.platform.workspace.domain.WorkspaceMembership;
import com.creastrix.platform.workspace.domain.WorkspaceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Workspace foundation.
 *
 * <p>The creator and the owning Organization are verified through the User and
 * Organization application layers: intentional application-to-application
 * dependencies inside the modular monolith. This service never touches User or
 * Organization persistence directly. Database enforcement remains
 * authoritative against races after these application-level validations.
 *
 * <p>Authorization boundary: this slice proves that the represented creator
 * User exists, is ACTIVE, and (for Organization-owned creation) holds an
 * ACTIVE OWNER Organization Membership. Authentication and caller identity
 * proof are not implemented, so this service enforces that the represented
 * creator and owner of a User-owned Workspace are the same identity, but
 * cannot prove that the external caller actually is that User.
 */
@Service
public class WorkspaceService {

    private final UserService userService;
    private final OrganizationService organizationService;
    private final WorkspaceRepository workspaces;

    public WorkspaceService(
            UserService userService,
            OrganizationService organizationService,
            WorkspaceRepository workspaces) {
        this.userService = userService;
        this.organizationService = organizationService;
        this.workspaces = workspaces;
    }

    /**
     * Creates a User-owned Workspace together with the owner's initial ACTIVE
     * ADMIN Workspace Membership in one transaction.
     *
     * <p>The API deliberately has no separate creator and owner: a User-owned
     * Workspace may be created only by its owner acting for that same User.
     * There is no create-on-behalf-of-another-User path in MVP.
     *
     * @throws com.creastrix.platform.user.domain.UserNotFoundException
     *         if the owner User does not exist
     * @throws WorkspaceCreatorNotActiveException
     *         if the owner User is not ACTIVE
     */
    @Transactional
    public Workspace createUserOwnedWorkspace(UUID ownerUserId) {
        User owner = userService.findUser(ownerUserId);
        if (owner.status() != UserStatus.ACTIVE) {
            throw new WorkspaceCreatorNotActiveException(owner.id(), owner.status());
        }
        UUID id = UUID.randomUUID();
        workspaces.createUserOwned(id, owner.id());
        return workspaces.findById(id).orElseThrow(() -> new WorkspaceNotFoundException(id));
    }

    /**
     * Creates an Organization-owned Workspace together with the creator's
     * initial ACTIVE ADMIN Workspace Membership in one transaction.
     *
     * @throws com.creastrix.platform.user.domain.UserNotFoundException
     *         if the creator User does not exist
     * @throws WorkspaceCreatorNotActiveException
     *         if the creator User is not ACTIVE
     * @throws com.creastrix.platform.organization.domain.OrganizationNotFoundException
     *         if the owning Organization does not exist
     * @throws WorkspaceCreatorNotOrganizationOwnerException
     *         if the creator has no ACTIVE OWNER Organization Membership in
     *         the owning Organization
     */
    @Transactional
    public Workspace createOrganizationOwnedWorkspace(UUID owningOrganizationId, UUID creatorUserId) {
        User creator = userService.findUser(creatorUserId);
        if (creator.status() != UserStatus.ACTIVE) {
            throw new WorkspaceCreatorNotActiveException(creator.id(), creator.status());
        }
        Organization organization = organizationService.findOrganization(owningOrganizationId);
        boolean creatorIsActiveOwner = organizationService.findMemberships(organization.id()).stream()
                .anyMatch(membership -> membership.userId().equals(creator.id())
                        && membership.role() == OrganizationRole.OWNER
                        && membership.status() == OrganizationMembershipStatus.ACTIVE);
        if (!creatorIsActiveOwner) {
            throw new WorkspaceCreatorNotOrganizationOwnerException(organization.id(), creator.id());
        }
        UUID id = UUID.randomUUID();
        workspaces.createOrganizationOwned(id, organization.id(), creator.id());
        return workspaces.findById(id).orElseThrow(() -> new WorkspaceNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Workspace findWorkspace(UUID workspaceId) {
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMembership> findMemberships(UUID workspaceId) {
        return workspaces.findMembershipsByWorkspaceId(workspaceId);
    }
}
