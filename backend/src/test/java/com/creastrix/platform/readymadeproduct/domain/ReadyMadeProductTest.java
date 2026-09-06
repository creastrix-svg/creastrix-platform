package com.creastrix.platform.readymadeproduct.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** Pure unit tests for the Ready-Made Product domain model invariants. */
class ReadyMadeProductTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID CREATED_BY_USER_ID = UUID.randomUUID();

    @Test
    void identityIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReadyMadeProduct(
                        null, WORKSPACE_ID, CREATED_BY_USER_ID, ReadyMadeProductStatus.ACTIVE, 1L))
                .withMessageContaining("id must not be null");
    }

    @Test
    void workspaceIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReadyMadeProduct(
                        ID, null, CREATED_BY_USER_ID, ReadyMadeProductStatus.ACTIVE, 1L))
                .withMessageContaining("workspaceId must not be null");
    }

    @Test
    void createdByUserIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReadyMadeProduct(
                        ID, WORKSPACE_ID, null, ReadyMadeProductStatus.ACTIVE, 1L))
                .withMessageContaining("createdByUserId must not be null");
    }

    @Test
    void lifecycleStatusIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReadyMadeProduct(
                        ID, WORKSPACE_ID, CREATED_BY_USER_ID, null, 1L))
                .withMessageContaining("status must not be null");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -7L, Long.MIN_VALUE})
    void negativeAvailableQuantityIsRejected(long negativeQuantity) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ReadyMadeProduct(
                        ID, WORKSPACE_ID, CREATED_BY_USER_ID,
                        ReadyMadeProductStatus.ACTIVE, negativeQuantity))
                .withMessageContaining("availableQuantity must not be negative");
    }

    @Test
    void zeroAvailableQuantityIsAllowed() {
        ReadyMadeProduct product = new ReadyMadeProduct(
                ID, WORKSPACE_ID, CREATED_BY_USER_ID, ReadyMadeProductStatus.ACTIVE, 0L);

        assertThat(product.availableQuantity()).isZero();
        assertThat(product.status()).isEqualTo(ReadyMadeProductStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 42L, Long.MAX_VALUE})
    void positiveAvailableQuantityIsAllowed(long positiveQuantity) {
        ReadyMadeProduct product = new ReadyMadeProduct(
                ID, WORKSPACE_ID, CREATED_BY_USER_ID,
                ReadyMadeProductStatus.ACTIVE, positiveQuantity);

        assertThat(product.availableQuantity()).isEqualTo(positiveQuantity);
    }

    /**
     * The domain model must be able to represent both recognized lifecycle
     * states, and lifecycle stays independent from stock availability: an
     * ACTIVE product may hold zero units and an ARCHIVED product may retain
     * units.
     */
    @ParameterizedTest
    @EnumSource(ReadyMadeProductStatus.class)
    void bothLifecycleStatesAreRepresentableIndependentlyFromQuantity(
            ReadyMadeProductStatus status) {
        ReadyMadeProduct withoutStock = new ReadyMadeProduct(
                ID, WORKSPACE_ID, CREATED_BY_USER_ID, status, 0L);
        ReadyMadeProduct withStock = new ReadyMadeProduct(
                ID, WORKSPACE_ID, CREATED_BY_USER_ID, status, 5L);

        assertThat(withoutStock.status()).isEqualTo(status);
        assertThat(withoutStock.availableQuantity()).isZero();
        assertThat(withStock.status()).isEqualTo(status);
        assertThat(withStock.availableQuantity()).isEqualTo(5L);
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "ACTIVE, ACTIVE, false",
            "ACTIVE, ARCHIVED, true",
            "ARCHIVED, ACTIVE, true",
            "ARCHIVED, ARCHIVED, false"
    })
    void lifecycleTransitionMatrixIsClosed(
            ReadyMadeProductStatus current,
            ReadyMadeProductStatus target,
            boolean allowed) {
        assertThat(current.canTransitionTo(target)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(ReadyMadeProductStatus.class)
    void nullIsNeverAValidLifecycleTarget(ReadyMadeProductStatus current) {
        assertThat(current.canTransitionTo(null)).isFalse();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"ACTIVE, ARCHIVED", "ARCHIVED, ACTIVE"})
    void supportedTransitionPreservesEveryOtherValue(
            ReadyMadeProductStatus currentStatus, ReadyMadeProductStatus targetStatus) {
        ReadyMadeProduct current = new ReadyMadeProduct(
                ID, WORKSPACE_ID, CREATED_BY_USER_ID, currentStatus, 42L);

        ReadyMadeProduct transitioned = current.transitionTo(targetStatus);

        assertThat(transitioned.id()).isEqualTo(ID);
        assertThat(transitioned.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(transitioned.createdByUserId()).isEqualTo(CREATED_BY_USER_ID);
        assertThat(transitioned.availableQuantity()).isEqualTo(42L);
        assertThat(transitioned.status()).isEqualTo(targetStatus);
        assertThat(current.status()).isEqualTo(currentStatus);
    }

    @ParameterizedTest(name = "{0} -> {1} rejected")
    @CsvSource({"ACTIVE, ACTIVE", "ARCHIVED, ARCHIVED"})
    void sameStateTransitionIsRejectedWithDiagnosticPayload(
            ReadyMadeProductStatus currentStatus, ReadyMadeProductStatus targetStatus) {
        ReadyMadeProduct product = new ReadyMadeProduct(
                ID, WORKSPACE_ID, CREATED_BY_USER_ID, currentStatus, 7L);

        assertThatExceptionOfType(InvalidReadyMadeProductStatusTransitionException.class)
                .isThrownBy(() -> product.transitionTo(targetStatus))
                .satisfies(exception -> {
                    assertThat(exception.readyMadeProductId()).isEqualTo(ID);
                    assertThat(exception.currentStatus()).isEqualTo(currentStatus);
                    assertThat(exception.targetStatus()).isEqualTo(targetStatus);
                });
    }

    @Test
    void nullTransitionTargetIsRejected() {
        ReadyMadeProduct product = new ReadyMadeProduct(
                ID, WORKSPACE_ID, CREATED_BY_USER_ID, ReadyMadeProductStatus.ACTIVE, 1L);

        assertThatNullPointerException()
                .isThrownBy(() -> product.transitionTo(null))
                .withMessage("Target Ready-Made Product status must not be null");
    }

    @Test
    void zeroManualDeltaIsInvalid() {
        ReadyMadeProduct product = product(ReadyMadeProductStatus.ACTIVE, 1L);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> product.applyManualQuantityDelta(0L))
                .withMessage("Manual quantity delta must not be zero");
    }

    @ParameterizedTest(name = "available={0}, delta={1}, result={2}")
    @CsvSource({
            "1, -1, 0",
            "0, 9223372036854775807, 9223372036854775807",
            "7, 5, 12"
    })
    void validManualDeltaUsesExactArithmeticAndPreservesOtherValues(
            long available, long delta, long expected) {
        ReadyMadeProduct original = product(ReadyMadeProductStatus.ACTIVE, available);

        ReadyMadeProduct changed = original.applyManualQuantityDelta(delta);

        assertThat(changed.id()).isEqualTo(original.id());
        assertThat(changed.workspaceId()).isEqualTo(original.workspaceId());
        assertThat(changed.createdByUserId()).isEqualTo(original.createdByUserId());
        assertThat(changed.status()).isEqualTo(original.status());
        assertThat(changed.availableQuantity()).isEqualTo(expected);
        assertThat(original.availableQuantity()).isEqualTo(available);
    }

    @ParameterizedTest
    @EnumSource(ReadyMadeProductStatus.class)
    void manualDeltaIsAllowedInBothLifecycleStates(ReadyMadeProductStatus status) {
        assertThat(product(status, 2L).applyManualQuantityDelta(1L).status())
                .isEqualTo(status);
    }

    @Test
    void zeroMinusOneIsUnderflow() {
        assertRejected(0L, -1L, ManualQuantityDeltaRejectionReason.UNDERFLOW);
    }

    @Test
    void longMinimumDeltaIsUnderflowWithoutNegation() {
        assertRejected(0L, Long.MIN_VALUE, ManualQuantityDeltaRejectionReason.UNDERFLOW);
    }

    @Test
    void maximumPlusOneIsOverflow() {
        assertRejected(Long.MAX_VALUE, 1L, ManualQuantityDeltaRejectionReason.OVERFLOW);
    }

    @Test
    void maximumPlusMaximumIsOverflow() {
        assertRejected(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                ManualQuantityDeltaRejectionReason.OVERFLOW);
    }

    @Test
    void resultReadModelRejectsInvalidOutcomeShape() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ManualQuantityDeltaResult(
                        ID, UUID.randomUUID(), 1L,
                        ManualQuantityDeltaCommandState.APPLIED,
                        null, null, null))
                .withMessageContaining("does not match state APPLIED");
    }

    @ParameterizedTest(name = "delta={0}, invalid reason={1}")
    @CsvSource({"2, UNDERFLOW", "-2, OVERFLOW"})
    void resultReadModelRejectsReasonIncompatibleWithDeltaSign(
            long delta, ManualQuantityDeltaRejectionReason reason) {
        UUID commandId = UUID.randomUUID();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ManualQuantityDeltaResult(
                        ID, commandId, delta, ManualQuantityDeltaCommandState.REJECTED,
                        null, reason, 3L))
                .withMessageContaining("rejection reason does not match delta sign");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ManualQuantityDeltaResult.rejected(
                        ID, commandId, delta, reason, 3L))
                .withMessageContaining("rejection reason does not match delta sign");
    }

    @ParameterizedTest(name = "delta={0}, valid reason={1}")
    @CsvSource({"-2, UNDERFLOW", "2, OVERFLOW"})
    void resultReadModelAcceptsReasonMatchingDeltaSign(
            long delta, ManualQuantityDeltaRejectionReason reason) {
        UUID commandId = UUID.randomUUID();
        ManualQuantityDeltaResult result = ManualQuantityDeltaResult.rejected(
                ID, commandId, delta, reason, 3L);

        assertThat(result).isEqualTo(new ManualQuantityDeltaResult(
                ID, commandId, delta, ManualQuantityDeltaCommandState.REJECTED,
                null, reason, 3L));
        assertThat(result.rejectionReason()).isEqualTo(reason);
        assertThat(result.delta()).isEqualTo(delta);
    }

    private void assertRejected(
            long available,
            long delta,
            ManualQuantityDeltaRejectionReason expectedReason) {
        ReadyMadeProduct product = product(ReadyMadeProductStatus.ARCHIVED, available);

        assertThatExceptionOfType(ReadyMadeProductManualQuantityDeltaRejectedException.class)
                .isThrownBy(() -> product.applyManualQuantityDelta(delta))
                .satisfies(rejection -> {
                    assertThat(rejection.reason()).isEqualTo(expectedReason);
                    assertThat(rejection.observedAvailableQuantity()).isEqualTo(available);
                });
        assertThat(product.availableQuantity()).isEqualTo(available);
    }

    private ReadyMadeProduct product(ReadyMadeProductStatus status, long available) {
        return new ReadyMadeProduct(
                ID, WORKSPACE_ID, CREATED_BY_USER_ID, status, available);
    }
}
