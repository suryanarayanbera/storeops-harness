package com.cognizant.storeops.activities.service;

import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the SLA breach sweep, bound from {@code storeops.activities.sla.sweep}.
 *
 * <p>Registered by {@code @ConfigurationPropertiesScan} on the application class rather than by
 * {@code @EnableConfigurationProperties}: the latter would name this type from the application root
 * and create a root-to-module dependency for no benefit.
 *
 * <p>Validation lives in the compact constructor and throws {@link ValidationError} rather than
 * {@code IllegalArgumentException}, because the StoreOps error contract admits no raw exceptions -
 * see {@code ModuleBoundaryTest} rules 6 and 6b. A misconfigured sweep therefore fails the context
 * at startup with a coded error instead of silently scheduling itself every zero milliseconds.
 *
 * @param enabled      false removes {@link SlaSweepScheduler} from the context entirely
 * @param interval     delay between the end of one sweep and the start of the next; must be positive
 * @param initialDelay wait before the first sweep after startup; zero is allowed
 */
@ConfigurationProperties(prefix = "storeops.activities.sla.sweep")
public record SlaSweepProperties(boolean enabled, Duration interval, Duration initialDelay) {

    public SlaSweepProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new ValidationError(
                    "SLA sweep interval must be a positive duration",
                    List.of("storeops.activities.sla.sweep.interval: must be positive, was " + interval));
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new ValidationError(
                    "SLA sweep initial delay must not be negative",
                    List.of("storeops.activities.sla.sweep.initial-delay: must not be negative, was " + initialDelay));
        }
    }
}
