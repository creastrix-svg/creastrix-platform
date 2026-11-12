package com.creastrix.platform.accountcart.assets;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static com.creastrix.platform.accountcart.assets.CartAssetRetention.Eligibility.LAST_REFERENCE_REMOVED;
import static com.creastrix.platform.accountcart.assets.CartAssetRetention.Eligibility.RETAIN;
import static com.creastrix.platform.accountcart.assets.CartAssetRetention.Eligibility.UNFINISHED_UPLOAD;
import static com.creastrix.platform.accountcart.assets.CartAssetRetention.PinHistory.NEVER_PINNED;
import static com.creastrix.platform.accountcart.assets.CartAssetRetention.PinHistory.PREVIOUSLY_PINNED;
import static com.creastrix.platform.accountcart.assets.CartAssetRetention.PinHistory.UNKNOWN;
import static com.creastrix.platform.accountcart.assets.CartAssetRetention.classify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CartAssetRetentionTest {
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant NEXT_DAY = START.plus(Duration.ofHours(24));
    private static final Instant DETACHED = START.plus(Duration.ofDays(30));
    private static final Instant DEADLINE = DETACHED.plus(Duration.ofDays(7));

    @Test
    void onlyUnfinishedNeverBoundUploadsReachThe24HourBoundaryFromServerStart() {
        var unfinished = new CartAssetRetention.Facts(START, false, false, NEVER_PINNED, 0, 0, null, false);
        assertThat(classify(unfinished, START.minusSeconds(1))).isEqualTo(RETAIN);
        assertThat(classify(unfinished, NEXT_DAY.minusNanos(1))).isEqualTo(RETAIN);
        assertThat(classify(unfinished, NEXT_DAY)).isEqualTo(UNFINISHED_UPLOAD);
        var completed = new CartAssetRetention.Facts(START, true, false, NEVER_PINNED, 0, 0, null, false);
        assertThat(classify(completed, DEADLINE)).isEqualTo(RETAIN);
    }

    @Test
    void confirmedReferencesKeepBytesAndRestartTheGracePeriodAtTheLatestRemoval() {
        var released = new CartAssetRetention.Facts(START, true, true, NEVER_PINNED, 0, 0, DETACHED, false);
        assertThat(classify(released, DEADLINE.minusNanos(1))).isEqualTo(RETAIN);
        assertThat(classify(released, DEADLINE)).isEqualTo(LAST_REFERENCE_REMOVED);
        var relinked = new CartAssetRetention.Facts(START, true, true, NEVER_PINNED, 1, 0, null, false);
        assertThat(classify(relinked, DEADLINE.plus(Duration.ofDays(100)))).isEqualTo(RETAIN);
        var releasedAgain = new CartAssetRetention.Facts(START, true, true, NEVER_PINNED, 0, 0, DEADLINE, false);
        assertThat(classify(releasedAgain, DEADLINE.plus(Duration.ofDays(6)))).isEqualTo(RETAIN);
        assertThat(classify(releasedAgain, DEADLINE.plus(Duration.ofDays(7)))).isEqualTo(LAST_REFERENCE_REMOVED);
    }

    @Test
    void pendingPinsProtectBothClocksAndUnknownLastRemovalNeverStartsGrace() {
        assertThat(classify(new CartAssetRetention.Facts(START, false, false, PREVIOUSLY_PINNED, 0, 1, null, false), DEADLINE)).isEqualTo(RETAIN);
        assertThat(classify(new CartAssetRetention.Facts(START, true, true, PREVIOUSLY_PINNED, 0, 2, DETACHED, false), DEADLINE)).isEqualTo(RETAIN);
        assertThat(classify(new CartAssetRetention.Facts(START, true, true, NEVER_PINNED, 0, 0, null, false), DEADLINE)).isEqualTo(RETAIN);
        assertThatIllegalArgumentException().isThrownBy(() -> new CartAssetRetention.Facts(START, false, true, NEVER_PINNED, 0, 0, null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new CartAssetRetention.Facts(START, true, false, NEVER_PINNED, 1, 0, null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new CartAssetRetention.Facts(START, true, true, PREVIOUSLY_PINNED, 0, -1, DETACHED, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new CartAssetRetention.Facts(START, true, true, NEVER_PINNED, 0, 1, DETACHED, false));
    }

    @Test
    void aFormerlyPendingOnlyAssetRequiresProvenTimingAndNeverUsesTheTwentyFourHourClock() {
        for (boolean completed : new boolean[]{false, true}) {
            var formerlyPinned = new CartAssetRetention.Facts(START, completed, false, PREVIOUSLY_PINNED, 0, 0, null, false);
            assertThat(classify(formerlyPinned, NEXT_DAY)).isEqualTo(RETAIN);
            assertThat(classify(formerlyPinned, DEADLINE.plus(Duration.ofDays(100)))).isEqualTo(RETAIN);
            var observed = new CartAssetRetention.Facts(START, completed, false, PREVIOUSLY_PINNED, 0, 0, DETACHED, false);
            assertThat(classify(observed, DEADLINE.minusNanos(1))).isEqualTo(RETAIN);
            assertThat(classify(observed, DEADLINE)).isEqualTo(LAST_REFERENCE_REMOVED);
        }
        var lastPinRemoved = DETACHED.plus(Duration.ofDays(6));
        var afterUnpin = new CartAssetRetention.Facts(START, true, true, PREVIOUSLY_PINNED, 0, 0, lastPinRemoved, false);
        assertThat(classify(afterUnpin, DEADLINE)).isEqualTo(RETAIN);
        assertThat(classify(afterUnpin, lastPinRemoved.plus(Duration.ofDays(7)))).isEqualTo(LAST_REFERENCE_REMOVED);
    }

    @Test
    void unresolvedCommitOrMissingHistoryPreventsBothDeadlines() {
        var heldUpload = new CartAssetRetention.Facts(START, false, false, NEVER_PINNED, 0, 0, null, true);
        var heldRelease = new CartAssetRetention.Facts(START, true, true, PREVIOUSLY_PINNED, 0, 0, DETACHED, true);
        var ambiguousUpload = new CartAssetRetention.Facts(START, false, false, UNKNOWN, 0, 0, null, false);
        var ambiguousRelease = new CartAssetRetention.Facts(START, true, true, UNKNOWN, 0, 0, DETACHED, false);
        for (var state : new CartAssetRetention.Facts[]{heldUpload, heldRelease, ambiguousUpload, ambiguousRelease}) {
            assertThat(classify(state, NEXT_DAY)).isEqualTo(RETAIN);
            assertThat(classify(state, DEADLINE)).isEqualTo(RETAIN);
            assertThat(classify(state, DEADLINE.plus(Duration.ofDays(100)))).isEqualTo(RETAIN);
        }
    }
}
