package com.creastrix.platform.workspace.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WorkspaceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void nullIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Workspace(null, WorkspaceOwnerType.USER, OWNER_ID))
                .withMessage("Workspace id must not be null");
    }

    @Test
    void nullOwnerTypeIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Workspace(WORKSPACE_ID, null, OWNER_ID))
                .withMessage("Workspace ownerType must not be null");
    }

    @Test
    void nullOwnerIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Workspace(WORKSPACE_ID, WorkspaceOwnerType.ORGANIZATION, null))
                .withMessage("Workspace ownerId must not be null");
    }
}
