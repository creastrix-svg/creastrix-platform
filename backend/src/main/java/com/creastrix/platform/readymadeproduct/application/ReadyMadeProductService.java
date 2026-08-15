package com.creastrix.platform.readymadeproduct.application;

import java.util.UUID;

import com.creastrix.platform.readymadeproduct.application.port.ReadyMadeProductRepository;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductActorNotActiveException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductActorNotAuthorizedException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductCreatorNotActiveException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductCreatorNotAuthorizedException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductNotFoundException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.application.WorkspaceService;
import com.creastrix.platform.workspace.domain.Workspace;
import com.creastrix.platform.workspace.domain.WorkspaceMembership;
import com.creastrix.platform.workspace.domain.WorkspacePermissionScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Ready-Made Product structural foundation.
 *
 * <p>The creator and the target Workspace are verified through the User and
 * Workspace application layers: intentional application-to-application
 * dependencies inside the modular monolith. This service never touches User or
 * Workspace persistence directly. Effective write authorization is decided by
 * the existing {@code WorkspaceMembership.allowsWorkspaceLayerWrite(...)}
 * semantics for the READY_MADE_PRODUCTS scope; no separate authorization rule
 * is reimplemented here. Database enforcement remains authoritative against
 * races after these application-level validations.
 *
 * <p>Identity boundary: authentication and external caller identity proof are
 * not implemented. {@code creatorUserId} is the identity presented to the
 * application, not an identity proven by an authenticated HTTP session. This
 * service therefore proves that the presented creator identity exists, is
 * ACTIVE, and holds effective READY_MADE_PRODUCTS write authorization, but it
 * cannot prove that the external caller actually is that User.
 *
 * <p>Coverage boundary: creation, lookup by identity, and the ACTIVE/ARCHIVED
 * lifecycle transitions belong to the implemented foundation. Manual quantity
 * deltas, allocation, release, and every commerce interaction are not
 * implemented.
 */
@Service
public class ReadyMadeProductService {

    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final ReadyMadeProductRepository readyMadeProducts;

    public ReadyMadeProductService(
            UserService userService,
            WorkspaceService workspaceService,
            ReadyMadeProductRepository readyMadeProducts) {
        this.userService = userService;
        this.workspaceService = workspaceService;
        this.readyMadeProducts = readyMadeProducts;
    }

    /**
     * Creates exactly one Ready-Made Product in the given Workspace as one
     * coherent creation result: the Workspace relationship, the immutable
     * Created By User, the initial ACTIVE lifecycle state, and the initial
     * available quantity.
     *
     * <p>The lifecycle state is not a creation input: ACTIVE is established
     * here, so no caller can request ARCHIVED creation.
     *
     * @param initialAvailableQuantity required non-negative initial available
     *        quantity; zero is valid
     * @throws com.creastrix.platform.user.domain.UserNotFoundException
     *         if the creator User does not exist
     * @throws ReadyMadeProductCreatorNotActiveException
     *         if the creator User is SUSPENDED or DEACTIVATED
     * @throws com.creastrix.platform.workspace.domain.WorkspaceNotFoundException
     *         if the target Workspace does not exist
     * @throws ReadyMadeProductCreatorNotAuthorizedException
     *         if the creator has no effective READY_MADE_PRODUCTS write
     *         authorization in the target Workspace
     * @throws IllegalArgumentException
     *         if the initial available quantity is negative
     */
    @Transactional
    public ReadyMadeProduct createReadyMadeProduct(
            UUID workspaceId, UUID creatorUserId, long initialAvailableQuantity) {
        User creator = userService.findUser(creatorUserId);
        if (creator.status() == UserStatus.SUSPENDED || creator.status() == UserStatus.DEACTIVATED) {
            throw new ReadyMadeProductCreatorNotActiveException(creator.id(), creator.status());
        }
        Workspace workspace = workspaceService.findWorkspace(workspaceId);
        WorkspaceMembership creatorMembership = workspaceService.findMemberships(workspace.id()).stream()
                .filter(membership -> membership.userId().equals(creator.id()))
                .findFirst()
                .orElseThrow(() -> new ReadyMadeProductCreatorNotAuthorizedException(
                        workspace.id(), creator.id()));
        if (!creatorMembership.allowsWorkspaceLayerWrite(
                creator.status(), WorkspacePermissionScope.READY_MADE_PRODUCTS)) {
            throw new ReadyMadeProductCreatorNotAuthorizedException(workspace.id(), creator.id());
        }
        UUID id = UUID.randomUUID();
        // The intended creation result is expressed as the domain model first,
        // so its own invariants (required references, ACTIVE established here,
        // non-negative quantity) are validated before anything is persisted.
        ReadyMadeProduct intended = new ReadyMadeProduct(
                id, workspace.id(), creator.id(),
                ReadyMadeProductStatus.ACTIVE, initialAvailableQuantity);
        readyMadeProducts.create(
                intended.id(), intended.workspaceId(), intended.createdByUserId(),
                intended.availableQuantity());
        return readyMadeProducts.findById(id)
                .orElseThrow(() -> new ReadyMadeProductNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public ReadyMadeProduct findReadyMadeProduct(UUID readyMadeProductId) {
        return readyMadeProducts.findById(readyMadeProductId)
                .orElseThrow(() -> new ReadyMadeProductNotFoundException(readyMadeProductId));
    }

    /**
     * Archives an ACTIVE Ready-Made Product for the represented actor User.
     *
     * <p>Error precedence is intentional for this internal application layer:
     * Product existence is checked before actor existence, authorization, and
     * lifecycle state. Authentication and HTTP do not exist yet; when they are
     * introduced, this disclosure boundary must be reviewed at that boundary.
     */
    @Transactional
    public ReadyMadeProduct archiveReadyMadeProduct(
            UUID readyMadeProductId, UUID actorUserId) {
        return transitionReadyMadeProduct(
                readyMadeProductId,
                actorUserId,
                ReadyMadeProductStatus.ACTIVE,
                ReadyMadeProductStatus.ARCHIVED);
    }

    /**
     * Activates an ARCHIVED Ready-Made Product for the represented actor User.
     *
     * <p>Error precedence is intentional for this internal application layer:
     * Product existence is checked before actor existence, authorization, and
     * lifecycle state. Authentication and HTTP do not exist yet; when they are
     * introduced, this disclosure boundary must be reviewed at that boundary.
     */
    @Transactional
    public ReadyMadeProduct activateReadyMadeProduct(
            UUID readyMadeProductId, UUID actorUserId) {
        return transitionReadyMadeProduct(
                readyMadeProductId,
                actorUserId,
                ReadyMadeProductStatus.ARCHIVED,
                ReadyMadeProductStatus.ACTIVE);
    }

    private ReadyMadeProduct transitionReadyMadeProduct(
            UUID readyMadeProductId,
            UUID actorUserId,
            ReadyMadeProductStatus expectedStatus,
            ReadyMadeProductStatus targetStatus) {
        ReadyMadeProduct current = readyMadeProducts.findById(readyMadeProductId)
                .orElseThrow(() -> new ReadyMadeProductNotFoundException(readyMadeProductId));

        User actor = userService.findUser(actorUserId);
        if (actor.status() != UserStatus.ACTIVE) {
            throw new ReadyMadeProductActorNotActiveException(actor.id(), actor.status());
        }

        WorkspaceMembership actorMembership = workspaceService.findMemberships(current.workspaceId()).stream()
                .filter(membership -> membership.userId().equals(actor.id()))
                .findFirst()
                .orElseThrow(() -> new ReadyMadeProductActorNotAuthorizedException(
                        current.id(), current.workspaceId(), actor.id()));
        if (!actorMembership.allowsWorkspaceLayerWrite(
                actor.status(), WorkspacePermissionScope.READY_MADE_PRODUCTS)) {
            throw new ReadyMadeProductActorNotAuthorizedException(
                    current.id(), current.workspaceId(), actor.id());
        }

        return readyMadeProducts.transitionStatus(
                current.id(), actor.id(), expectedStatus, targetStatus);
    }
}
