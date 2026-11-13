package com.creastrix.platform.accountcart.assets;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Classifies an authoritative asset snapshot only; no scheduler or file deletion is exposed.
 * Counts, timestamps and history come from the server. A future collector must re-read and
 * serialize against new references and transfer pins, not act on an earlier classification.
 */
public final class CartAssetRetention {
    private CartAssetRetention() {}

    public enum Eligibility { RETAIN, UNFINISHED_UPLOAD, LAST_REFERENCE_REMOVED }

    /** Current pin counts cannot distinguish a never-linked upload from a retired transfer. */
    public enum PinHistory { NEVER_PINNED, PREVIOUSLY_PINNED, UNKNOWN }

    public record Facts(Instant uploadStartedAt, boolean uploadCompleted, boolean everConfirmed,
                        PinHistory pinHistory, long confirmedLinks, long pendingPins,
                        Instant unreferencedObservedAt, boolean reconciliationRequired) {
        public Facts {
            Objects.requireNonNull(uploadStartedAt);
            Objects.requireNonNull(pinHistory);
            if (confirmedLinks < 0 || pendingPins < 0
                    || pendingPins > 0 && pinHistory == PinHistory.NEVER_PINNED
                    || confirmedLinks > 0 && !everConfirmed
                    || everConfirmed && !uploadCompleted
                    || unreferencedObservedAt != null && (unreferencedObservedAt.isBefore(uploadStartedAt)
                        || !everConfirmed && pinHistory == PinHistory.NEVER_PINNED))
                throw new IllegalArgumentException("Inconsistent asset history");
        }
    }

    public static Eligibility classify(Facts state, Instant serverNow) {
        Objects.requireNonNull(serverNow);
        if (state.reconciliationRequired() || state.pinHistory() == PinHistory.UNKNOWN
                || state.confirmedLinks() > 0 || state.pendingPins() > 0) return Eligibility.RETAIN;
        if (state.everConfirmed() || state.pinHistory() == PinHistory.PREVIOUSLY_PINNED) {
            // The first proven unreferenced observation is at or after the final COMMIT.
            // Reaching its deadline may be late, but can never shorten the seven days.
            var last = state.unreferencedObservedAt();
            if (last != null && Duration.between(last, serverNow).compareTo(Duration.ofDays(7)) >= 0)
                return Eligibility.LAST_REFERENCE_REMOVED;
        } else if (!state.uploadCompleted() && state.pinHistory() == PinHistory.NEVER_PINNED
                && Duration.between(state.uploadStartedAt(), serverNow).compareTo(Duration.ofHours(24)) >= 0) {
            return Eligibility.UNFINISHED_UPLOAD;
        }
        return Eligibility.RETAIN;
    }
}
