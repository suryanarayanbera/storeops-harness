package com.cognizant.storeops.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The grace period's defaulting rules.
 *
 * <p>Both substitutions exist so that misconfiguration cannot turn escalation into something worse than
 * the default. An absent property is the ordinary case. A negative one would make every second
 * observation escalate immediately, which reads to a store manager as an escalation nobody was given a
 * chance to prevent - so it is treated as absent rather than obeyed.
 */
class SlaEscalationPropertiesTest {

    @Test
    @DisplayName("a configured grace period is used as given")
    void configuredGracePeriodIsUsed() {
        assertThat(new SlaEscalationProperties(Duration.ofMinutes(30)).gracePeriod())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(new SlaEscalationProperties(Duration.ZERO).gracePeriod()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("an absent grace period falls back to two hours")
    void absentGracePeriodFallsBackToTheDefault() {
        assertThat(new SlaEscalationProperties(null).gracePeriod()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("a negative grace period falls back to two hours rather than escalating instantly")
    void negativeGracePeriodFallsBackToTheDefault() {
        assertThat(new SlaEscalationProperties(Duration.ofHours(-1)).gracePeriod())
                .isEqualTo(Duration.ofHours(2));
    }
}
