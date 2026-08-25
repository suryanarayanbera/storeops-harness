package com.cognizant.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Proves the sweep is wired into the running application, and that the switch keeping it out of the
 * test suite actually removes it.
 *
 * <p>Reading the {@code @Scheduled} annotation by reflection is the only honest way to assert the
 * cadence. Waiting for the sweep to fire would mean a fifteen minute test, and shortening the
 * interval so it fires would test the shortened value rather than the shipped one.
 *
 * <p>The conditional-bean cases use {@link ApplicationContextRunner} rather than a second
 * {@code @SpringBootTest}. That is not only faster: every distinct {@code @SpringBootTest}
 * configuration adds a cached context against the JVM-wide H2 database, and context build order is
 * what makes seed-data assertions elsewhere in the suite fragile. One new full context is enough.
 */
class SlaSweepWiringTest {

    private static final String SWEEP_METHOD = "sweepOverdueActivities";
    private static final String ENABLED_KEY = "storeops.activities.sla.sweep.enabled";

    /**
     * {@code SlaSweepScheduler} is registered as a component class, not built by a {@code @Bean}
     * method. That distinction is the whole test: a class-level {@code @ConditionalOnProperty} is
     * evaluated when the class is registered as a component and ignored when an explicit
     * {@code @Bean} method instantiates it, so a {@code @Bean} method here would register the
     * scheduler unconditionally and assert nothing.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SweepTestConfiguration.class, SlaSweepScheduler.class)
            .withPropertyValues(
                    "storeops.activities.sla.sweep.interval=PT15M",
                    "storeops.activities.sla.sweep.initial-delay=PT15M");

    /** Supplies the mocked collaborator so the conditional can be exercised without a full context. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SlaSweepProperties.class)
    static class SweepTestConfiguration {

        @Bean
        TaskService taskService() {
            return mock(TaskService.class);
        }
    }

    @Test
    @DisplayName("the sweep bean is registered when the property is absent, so the default is on")
    void schedulerIsRegisteredWhenThePropertyIsAbsent() {
        runner.run(context -> assertThat(context).hasSingleBean(SlaSweepScheduler.class));
    }

    @Test
    @DisplayName("the sweep bean is registered when the property is explicitly true")
    void schedulerIsRegisteredWhenEnabled() {
        runner.withPropertyValues(ENABLED_KEY + "=true")
                .run(context -> assertThat(context).hasSingleBean(SlaSweepScheduler.class));
    }

    @Test
    @DisplayName("the sweep bean is absent when disabled - no scheduled work exists to fire")
    void schedulerIsAbsentWhenDisabled() {
        runner.withPropertyValues(ENABLED_KEY + "=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SlaSweepScheduler.class);
                    // The properties still bind and still validate, so re-enabling needs no other change.
                    assertThat(context).hasSingleBean(SlaSweepProperties.class);
                    assertThat(context.getBean(SlaSweepProperties.class).enabled()).isFalse();
                });
    }

    @Test
    @DisplayName("the sweep is scheduled from the configured properties, not from hard-coded literals")
    void sweepMethodIsScheduledFromProperties() throws NoSuchMethodException {
        final Scheduled scheduled = SlaSweepScheduler.class
                .getDeclaredMethod(SWEEP_METHOD)
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${storeops.activities.sla.sweep.interval}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${storeops.activities.sla.sweep.initial-delay}");
        // fixedDelay, not fixedRate: an overrunning sweep must not queue the next one behind it.
        assertThat(scheduled.fixedRateString()).isEmpty();
    }

    /**
     * The one full-context case: that the shipped {@code application.yml} really does enable the
     * sweep and really does bind fifteen minutes. No property override, because overriding the
     * values under test would prove nothing about what ships.
     */
    @Nested
    @SpringBootTest
    @DisplayName("with the shipped application.yml, unmodified")
    class ShippedDefaults {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private SlaSweepProperties properties;

        @Test
        @DisplayName("the scheduler is a live bean in the real application")
        void schedulerBeanIsRegistered() {
            assertThat(context.getBeansOfType(SlaSweepScheduler.class)).hasSize(1);
        }

        @Test
        @DisplayName("the shipped defaults are an enabled sweep every fifteen minutes")
        void configurationBindsTheShippedDefaults() {
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.interval()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.initialDelay()).isEqualTo(Duration.ofMinutes(15));
        }
    }
}
