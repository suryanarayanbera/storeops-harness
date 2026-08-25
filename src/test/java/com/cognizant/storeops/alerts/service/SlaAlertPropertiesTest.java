package com.cognizant.storeops.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Validation and binding of the escalation grace period.
 *
 * <p>A negative grace period would escalate a breach before the breach alert itself was raised.
 * Rejecting it at construction turns that into a startup failure carrying a code, rather than
 * behaviour nobody notices until a store manager is told about something the lead has not seen.
 *
 * <p>Zero is deliberately legal and is tested as such: it is how the integration tests drive both
 * stages inside one test method, and it is a reasonable production setting for a store that wants
 * escalation on the following sweep.
 *
 * <p>The override case uses {@link ApplicationContextRunner} rather than a second
 * {@code @SpringBootTest}. Each distinct {@code @SpringBootTest} property set adds a cached context
 * against the JVM-wide H2 database, and context build order is what has made seed-data assertions in
 * this suite fragile before now. The runner binds the real key through the real relaxed-binding
 * machinery, which is the whole claim being made.
 */
class SlaAlertPropertiesTest {

    private static final String GRACE_KEY = "storeops.alerts.sla.grace-period";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GraceConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SlaAlertProperties.class)
    static class GraceConfiguration {
    }

    @Test
    @DisplayName("a positive grace period is accepted")
    void acceptsAPositiveGracePeriod() {
        assertThat(new SlaAlertProperties(Duration.ofHours(4)).gracePeriod())
                .isEqualTo(Duration.ofHours(4));
    }

    @Test
    @DisplayName("a zero grace period is legal - escalate on the next sweep")
    void acceptsAZeroGracePeriod() {
        assertThat(new SlaAlertProperties(Duration.ZERO).gracePeriod()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a negative grace period is a typed ValidationError, not an IllegalArgumentException")
    void rejectsANegativeGracePeriod() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> new SlaAlertProperties(Duration.ofMinutes(-1)))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.getStatusCode()).isEqualTo(400);
                    assertThat(error.getDetails()).hasSize(1);
                });
    }

    @Test
    @DisplayName("a negative grace period of one second is rejected too, not rounded away")
    void rejectsAOneSecondNegativeGracePeriod() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> new SlaAlertProperties(Duration.ofSeconds(-1)))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a missing grace period is rejected rather than defaulted")
    void rejectsAMissingGracePeriod() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> new SlaAlertProperties(null))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("the grace period is genuinely bound from configuration, not a hard-coded constant")
    void gracePeriodIsBoundFromConfiguration() {
        runner.withPropertyValues(GRACE_KEY + "=PT1S").run(context -> assertThat(context)
                .getBean(SlaAlertProperties.class)
                .extracting(SlaAlertProperties::gracePeriod)
                .isEqualTo(Duration.ofSeconds(1)));

        runner.withPropertyValues(GRACE_KEY + "=PT30M").run(context -> assertThat(context)
                .getBean(SlaAlertProperties.class)
                .extracting(SlaAlertProperties::gracePeriod)
                .isEqualTo(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("a negative value in configuration fails the context rather than binding")
    void aNegativeConfiguredValueFailsTheContext() {
        runner.withPropertyValues(GRACE_KEY + "=PT-1H")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * The one full-context case, and it reuses an existing cached context: the property set here is
     * byte-identical to the one seven other integration classes already declare, so no new context is
     * built. No grace-period override, because overriding the value under test would prove nothing
     * about what ships.
     */
    @Nested
    @SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")
    @DisplayName("with the shipped application.yml, unmodified")
    class ShippedDefault {

        @Autowired
        private SlaAlertProperties properties;

        @Test
        @DisplayName("the shipped grace period is four hours")
        void shippedGracePeriodIsFourHours() {
            assertThat(properties.gracePeriod()).isEqualTo(Duration.ofHours(4));
        }
    }
}
