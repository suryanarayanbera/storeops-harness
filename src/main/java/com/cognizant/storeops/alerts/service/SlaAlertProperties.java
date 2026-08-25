package com.cognizant.storeops.alerts.service;

import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for SLA breach escalation, bound from {@code storeops.alerts.sla}.
 *
 * <p>Owned by the alerts module because the grace period is an alerting policy, not an activity
 * property. The activities module reports that an activity is overdue and holds no opinion about how
 * long anyone has to deal with it.
 *
 * <p>Validation throws {@link ValidationError} rather than {@code IllegalArgumentException}, because
 * the StoreOps error contract admits no raw exceptions - see {@code ModuleBoundaryTest} rules 6 and
 * 6b.
 *
 * @param gracePeriod how long a breach may stay unresolved before escalating to the store manager.
 *                    Zero is valid and means "escalate on the next sweep"; negative is not, because
 *                    it would escalate before the breach was even alerted
 */
@ConfigurationProperties(prefix = "storeops.alerts.sla")
public record SlaAlertProperties(Duration gracePeriod) {

    public SlaAlertProperties {
        if (gracePeriod == null || gracePeriod.isNegative()) {
            throw new ValidationError(
                    "SLA grace period must not be negative",
                    List.of("storeops.alerts.sla.grace-period: must not be negative, was " + gracePeriod));
        }
    }
}
