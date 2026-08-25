package com.cognizant.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validation test for the sweep configuration.
 *
 * <p>A zero or negative interval would schedule the sweep continuously, and the failure would show
 * up as a pegged CPU rather than as an error. Rejecting it at construction turns a runtime symptom
 * into a startup failure carrying a code.
 */
class SlaSweepPropertiesTest {

    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

    @Test
    @DisplayName("a positive interval and a non-negative initial delay are accepted")
    void acceptsAValidConfiguration() {
        final SlaSweepProperties properties =
                new SlaSweepProperties(true, FIFTEEN_MINUTES, Duration.ofMinutes(5));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.interval()).isEqualTo(FIFTEEN_MINUTES);
        assertThat(properties.initialDelay()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("a zero initial delay is legal - sweep immediately on startup")
    void acceptsAZeroInitialDelay() {
        assertThat(new SlaSweepProperties(true, FIFTEEN_MINUTES, Duration.ZERO).initialDelay())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a zero interval is rejected as a typed ValidationError, not an IllegalArgumentException")
    void rejectsAZeroInterval() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> new SlaSweepProperties(true, Duration.ZERO, FIFTEEN_MINUTES))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.getStatusCode()).isEqualTo(400);
                    assertThat(error.getDetails()).hasSize(1);
                });
    }

    @Test
    @DisplayName("a negative interval is rejected")
    void rejectsANegativeInterval() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> new SlaSweepProperties(true, Duration.ofMinutes(-1), FIFTEEN_MINUTES))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a missing interval is rejected rather than defaulted")
    void rejectsAMissingInterval() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> new SlaSweepProperties(true, null, FIFTEEN_MINUTES))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a negative initial delay is rejected")
    void rejectsANegativeInitialDelay() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> new SlaSweepProperties(true, FIFTEEN_MINUTES, Duration.ofSeconds(-1)))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.getStatusCode()).isEqualTo(400);
                });
    }

    @Test
    @DisplayName("a missing initial delay is rejected rather than defaulted")
    void rejectsAMissingInitialDelay() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> new SlaSweepProperties(true, FIFTEEN_MINUTES, null))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED"));
    }
}
