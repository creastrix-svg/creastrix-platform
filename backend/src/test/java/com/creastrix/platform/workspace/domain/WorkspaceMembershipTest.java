package com.creastrix.platform.workspace.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.creastrix.platform.user.domain.UserStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceMembershipTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void nullWorkspaceIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkspaceMembership(
                        null, USER_ID, WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of()))
                .withMessage("Workspace Membership workspaceId must not be null");
    }

    @Test
    void nullUserIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkspaceMembership(
                        WORKSPACE_ID, null, WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of()))
                .withMessage("Workspace Membership userId must not be null");
    }

    @Test
    void nullRoleIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkspaceMembership(
                        WORKSPACE_ID, USER_ID, null, WorkspaceMembershipStatus.ACTIVE, Set.of()))
                .withMessage("Workspace Membership role must not be null");
    }

    @Test
    void nullStatusIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkspaceMembership(
                        WORKSPACE_ID, USER_ID, WorkspaceRole.ADMIN, null, Set.of()))
                .withMessage("Workspace Membership status must not be null");
    }

    @Test
    void nullScopeCollectionIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkspaceMembership(
                        WORKSPACE_ID, USER_ID, WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, null))
                .withMessage("Workspace Membership grantedScopes must not be null");
    }

    @Test
    void nullScopeElementIsRejected() {
        Set<WorkspacePermissionScope> scopes = new HashSet<>();
        scopes.add(WorkspacePermissionScope.PROJECTS);
        scopes.add(null);

        assertThatNullPointerException()
                .isThrownBy(() -> new WorkspaceMembership(
                        WORKSPACE_ID, USER_ID, WorkspaceRole.EDITOR, WorkspaceMembershipStatus.ACTIVE, scopes))
                .withMessage("Workspace Membership granted scope must not be null");
    }

    @Test
    void scopeCollectionIsDefensivelyImmutable() {
        Set<WorkspacePermissionScope> source = new HashSet<>();
        source.add(WorkspacePermissionScope.PROJECTS);
        WorkspaceMembership membership = new WorkspaceMembership(
                WORKSPACE_ID, USER_ID, WorkspaceRole.EDITOR, WorkspaceMembershipStatus.ACTIVE, source);

        // A later mutation of the source collection does not leak into the record.
        source.add(WorkspacePermissionScope.LISTINGS);
        assertThat(membership.grantedScopes()).containsExactly(WorkspacePermissionScope.PROJECTS);

        // The exposed set itself is immutable.
        assertThatThrownBy(() -> membership.grantedScopes().add(WorkspacePermissionScope.LISTINGS))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void activeAdminOfActiveUserReadsAndWritesAllCurrentScopesWithoutGrants() {
        WorkspaceMembership admin = membership(
                WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of());

        for (WorkspacePermissionScope scope : WorkspacePermissionScope.values()) {
            assertThat(admin.allowsWorkspaceLayerRead(UserStatus.ACTIVE, scope)).isTrue();
            assertThat(admin.allowsWorkspaceLayerWrite(UserStatus.ACTIVE, scope)).isTrue();
        }
    }

    @Test
    void activeEditorReadsAndWritesOnlyGrantedScopes() {
        WorkspaceMembership editor = membership(
                WorkspaceRole.EDITOR, WorkspaceMembershipStatus.ACTIVE,
                Set.of(WorkspacePermissionScope.PROJECTS));

        assertThat(editor.allowsWorkspaceLayerRead(UserStatus.ACTIVE, WorkspacePermissionScope.PROJECTS)).isTrue();
        assertThat(editor.allowsWorkspaceLayerWrite(UserStatus.ACTIVE, WorkspacePermissionScope.PROJECTS)).isTrue();
        assertThat(editor.allowsWorkspaceLayerRead(
                UserStatus.ACTIVE, WorkspacePermissionScope.READY_MADE_PRODUCTS)).isFalse();
        assertThat(editor.allowsWorkspaceLayerWrite(
                UserStatus.ACTIVE, WorkspacePermissionScope.READY_MADE_PRODUCTS)).isFalse();
        assertThat(editor.allowsWorkspaceLayerRead(UserStatus.ACTIVE, WorkspacePermissionScope.LISTINGS)).isFalse();
        assertThat(editor.allowsWorkspaceLayerWrite(UserStatus.ACTIVE, WorkspacePermissionScope.LISTINGS)).isFalse();
    }

    @Test
    void activeViewerReadsOnlyGrantedScopesAndNeverWrites() {
        WorkspaceMembership viewer = membership(
                WorkspaceRole.VIEWER, WorkspaceMembershipStatus.ACTIVE,
                Set.of(WorkspacePermissionScope.LISTINGS));

        assertThat(viewer.allowsWorkspaceLayerRead(UserStatus.ACTIVE, WorkspacePermissionScope.LISTINGS)).isTrue();
        assertThat(viewer.allowsWorkspaceLayerWrite(UserStatus.ACTIVE, WorkspacePermissionScope.LISTINGS)).isFalse();
        assertThat(viewer.allowsWorkspaceLayerRead(UserStatus.ACTIVE, WorkspacePermissionScope.PROJECTS)).isFalse();
        assertThat(viewer.allowsWorkspaceLayerWrite(UserStatus.ACTIVE, WorkspacePermissionScope.PROJECTS)).isFalse();
    }

    @Test
    void oneScopeNeverGrantsAnother() {
        for (WorkspacePermissionScope granted : WorkspacePermissionScope.values()) {
            WorkspaceMembership editor = membership(
                    WorkspaceRole.EDITOR, WorkspaceMembershipStatus.ACTIVE, Set.of(granted));
            for (WorkspacePermissionScope requested : WorkspacePermissionScope.values()) {
                boolean expected = requested == granted;
                assertThat(editor.allowsWorkspaceLayerRead(UserStatus.ACTIVE, requested)).isEqualTo(expected);
                assertThat(editor.allowsWorkspaceLayerWrite(UserStatus.ACTIVE, requested)).isEqualTo(expected);
            }
        }
    }

    @Test
    void invitedMembershipProvidesNoAccess() {
        WorkspaceMembership invited = membership(
                WorkspaceRole.ADMIN, WorkspaceMembershipStatus.INVITED, Set.of());
        assertNoAccess(invited, UserStatus.ACTIVE);
    }

    @Test
    void suspendedMembershipProvidesNoAccess() {
        WorkspaceMembership suspended = membership(
                WorkspaceRole.ADMIN, WorkspaceMembershipStatus.SUSPENDED,
                Set.of(WorkspacePermissionScope.PROJECTS));
        assertNoAccess(suspended, UserStatus.ACTIVE);
    }

    @Test
    void suspendedUserProvidesNoAccess() {
        WorkspaceMembership admin = membership(
                WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of());
        assertNoAccess(admin, UserStatus.SUSPENDED);
    }

    @Test
    void deactivatedUserProvidesNoAccess() {
        WorkspaceMembership editor = membership(
                WorkspaceRole.EDITOR, WorkspaceMembershipStatus.ACTIVE,
                Set.of(WorkspacePermissionScope.PROJECTS));
        assertNoAccess(editor, UserStatus.DEACTIVATED);
    }

    @Test
    void nullPredicateInputsAreRejected() {
        WorkspaceMembership admin = membership(
                WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of());

        assertThatNullPointerException()
                .isThrownBy(() -> admin.allowsWorkspaceLayerRead(null, WorkspacePermissionScope.PROJECTS))
                .withMessage("userStatus must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> admin.allowsWorkspaceLayerRead(UserStatus.ACTIVE, null))
                .withMessage("scope must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> admin.allowsWorkspaceLayerWrite(null, WorkspacePermissionScope.PROJECTS))
                .withMessage("userStatus must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> admin.allowsWorkspaceLayerWrite(UserStatus.ACTIVE, null))
                .withMessage("scope must not be null");
    }

    private static void assertNoAccess(WorkspaceMembership membership, UserStatus userStatus) {
        for (WorkspacePermissionScope scope : WorkspacePermissionScope.values()) {
            assertThat(membership.allowsWorkspaceLayerRead(userStatus, scope)).isFalse();
            assertThat(membership.allowsWorkspaceLayerWrite(userStatus, scope)).isFalse();
        }
    }

    private static WorkspaceMembership membership(
            WorkspaceRole role, WorkspaceMembershipStatus status, Set<WorkspacePermissionScope> scopes) {
        return new WorkspaceMembership(WORKSPACE_ID, USER_ID, role, status, scopes);
    }
}
