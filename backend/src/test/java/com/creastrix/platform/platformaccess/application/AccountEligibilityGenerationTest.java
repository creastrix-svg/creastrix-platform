package com.creastrix.platform.platformaccess.application;

import java.util.UUID;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Stamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountEligibilityGenerationTest {

    @ParameterizedTest
    @MethodSource("positiveBoundaryCounters")
    void exactTagAndEveryPositiveBitRoundTripWithoutTruncation(long generation) {
        UUID encoded = AccountEligibilityGeneration.encode(generation);

        assertThat(encoded.getMostSignificantBits()).isEqualTo(0x435258454c494731L);
        assertThat(encoded.getLeastSignificantBits()).isEqualTo(generation);
        assertThat(AccountEligibilityGeneration.decode(encoded)).isEqualTo(generation);
    }

    static LongStream positiveBoundaryCounters() {
        return LongStream.concat(LongStream.range(0, 63).map(bit -> 1L << bit),
                LongStream.of(Long.MAX_VALUE - 1, Long.MAX_VALUE)).distinct();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void zeroAndNegativeValuesAreRejectedRatherThanWrapped(long generation) {
        assertThatThrownBy(() -> AccountEligibilityGeneration.encode(generation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account eligibility generation must be positive.");
    }

    @ParameterizedTest
    @MethodSource("invalidEncodings")
    void inverseRejectsMissingWrongVersionAndNonpositiveLowerHalf(UUID value) {
        assertThatThrownBy(() -> AccountEligibilityGeneration.decode(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported account eligibility generation encoding.");
    }

    static Stream<Arguments> invalidEncodings() {
        return Stream.of(
                Arguments.of((UUID) null),
                Arguments.of(new UUID(0, 1)),
                Arguments.of(new UUID(0x435258454c494730L, 1)),
                Arguments.of(new UUID(0x435258454c494732L, 1)),
                Arguments.of(new UUID(0x435258454c494731L, 0)),
                Arguments.of(new UUID(0x435258454c494731L, -1)),
                Arguments.of(new UUID(0x435258454c494731L, Long.MIN_VALUE)));
    }

    @Test
    void adjacentMaximumValuesAndLowValuesStayDistinct() {
        assertThat(LongStream.of(1, 2, Integer.MAX_VALUE, 1L << 32,
                        Long.MAX_VALUE - 1, Long.MAX_VALUE)
                .mapToObj(AccountEligibilityGeneration::encode).distinct().count()).isEqualTo(6);
    }

    @Test
    void equalPerUserCountersDoNotCollapseFullUserBoundStamps() {
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        UUID firstGeneration = AccountEligibilityGeneration.encode(1);
        UUID secondGeneration = AccountEligibilityGeneration.encode(1);

        assertThat(firstGeneration).isEqualTo(secondGeneration);
        assertThat(new Stamp(firstUser, firstGeneration, session, grant, 1))
                .isNotEqualTo(new Stamp(secondUser, secondGeneration, session, grant, 1));
    }
}
