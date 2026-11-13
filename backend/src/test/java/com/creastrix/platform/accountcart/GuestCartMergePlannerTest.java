package com.creastrix.platform.accountcart;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.creastrix.platform.accountcart.GuestCartMergePlanner.*;
import static org.assertj.core.api.Assertions.*;

class GuestCartMergePlannerTest {
    private static final Instant FIRST = Instant.EPOCH;
    private static final LineKey BLUE = new LineKey("cup", "blue", Plain.INSTANCE);
    private static final LineKey RED = new LineKey("cup", "red", Plain.INSTANCE);

    private static LineKey decorated(byte... bytes) {
        return new LineKey("cup", "blue", new CanonicalDesign(bytes));
    }

    @Test
    void combinesOnlyTouchedLinesOnceRetainingTheEarliestThenTheStableId() {
        var current = List.of(
                new ExistingLine("z", FIRST, BLUE, 2),
                new ExistingLine("0", FIRST.plusSeconds(1), BLUE, 3),
                new ExistingLine("a", FIRST, BLUE, 1),
                new ExistingLine("old-red", FIRST, RED, 1),
                new ExistingLine("other-red", FIRST, RED, 1));
        var additions = List.of(new Units(BLUE, 1), new Units(BLUE, 2));
        var expected = new Plan(List.of(), List.of(new Replacement("a", 9, List.of("z", "0"))));
        assertThat(plan(current, additions)).isEqualTo(expected);
        assertThat(plan(current.reversed(), additions.reversed())).isEqualTo(expected);
    }

    @Test
    void leavesEvenPreexistingEquivalentRowsAloneForAnEmptyBatch() {
        assertThat(plan(List.of(new ExistingLine("1", FIRST, BLUE, 2),
                        new ExistingLine("2", FIRST, BLUE, 3)), List.of()))
                .isEqualTo(new Plan(List.of(), List.of()));
    }

    @Test
    void matchesExactBytesAndKeepsOtherOffersVariantsAndPlainSeparate() {
        var bytes = new byte[]{1, 2};
        var existingDesign = decorated(bytes);
        var different = decorated((byte) 1, (byte) 3);
        var otherOffer = new LineKey("other", "blue", existingDesign.design());
        var result = plan(List.of(new ExistingLine("engraved", FIRST, existingDesign, 2)),
                List.of(new Units(decorated(bytes.clone()), 1), new Units(BLUE, 1),
                        new Units(different, 4), new Units(BLUE, 2),
                        new Units(otherOffer, 1), new Units(RED, 1)));
        assertThat(result.replacements()).containsExactly(new Replacement("engraved", 3, List.of()));
        assertThat(result.insertions()).containsExactlyInAnyOrder(new Units(BLUE, 3),
                new Units(different, 4), new Units(otherOffer, 1), new Units(RED, 1));
    }

    @Test
    void collidingHashCodesDoNotIdentifyEqualDesigns() {
        var one = decorated((byte) 0, (byte) 31);
        var two = decorated((byte) 1, (byte) 0);
        assertThat(one.hashCode()).isEqualTo(two.hashCode());
        assertThat(plan(List.of(), List.of(new Units(one, 2), new Units(two, 3))).insertions())
                .containsExactlyInAnyOrder(new Units(one, 2), new Units(two, 3));
    }

    @Test
    void changingSourceOrReturnedBytesCannotChangeEqualityOrThePlan() {
        byte[] bytes = {1, 2};
        var design = new CanonicalDesign(bytes);
        var expected = new CanonicalDesign(bytes);
        bytes[0] = 8;
        design.bytes()[0] = 9;
        assertThat(design).isEqualTo(expected);
        var key = new LineKey("cup", "blue", design);
        var retained = plan(List.of(new ExistingLine("old", FIRST, key, 3),
                        new ExistingLine("older", FIRST.minusSeconds(1), key, 1)),
                List.of(new Units(key, 2))).replacements();
        assertThatThrownBy(retained::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(retained.getFirst().redundantIds()::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(retained).containsExactly(new Replacement("older", 6, List.of("old")));
    }

    @Test
    void rejectsOverflowBothWithinOneBatchAndAgainstTheCurrentCart() {
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> plan(List.of(), List.of(new Units(BLUE, Long.MAX_VALUE), new Units(BLUE, 1))));
        var current = List.of(new ExistingLine("id", FIRST, BLUE, Long.MAX_VALUE));
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> plan(current, List.of(new Units(RED, 1), new Units(BLUE, 1))));
        assertThat(current.getFirst().quantity()).isEqualTo(Long.MAX_VALUE);
        assertThat(plan(List.of(), List.of(new Units(BLUE, Long.MAX_VALUE))).insertions())
                .containsExactly(new Units(BLUE, Long.MAX_VALUE));
    }

    @Test
    void rejectsAnAmbiguousExistingIdentityRatherThanDoubleCounting() {
        var collision = List.of(new ExistingLine("same", FIRST, BLUE, 1),
                new ExistingLine("same", FIRST, RED, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> plan(collision, List.of(new Units(BLUE, 1))));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void onlyPositiveLineQuantitiesCanEnterThePlan(long quantity) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Units(BLUE, quantity));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExistingLine("id", FIRST, BLUE, quantity));
    }

    @Test
    void rejectsUnknownDesignAndMissingIdentityRatherThanAssumingPlain() {
        assertThatNullPointerException().isThrownBy(() -> new LineKey("offer", "variant", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new CanonicalDesign(new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> new LineKey(" ", "variant", Plain.INSTANCE));
        assertThatIllegalArgumentException().isThrownBy(() -> new LineKey("offer", "", Plain.INSTANCE));
    }
}
