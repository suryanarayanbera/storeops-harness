package com.cognizant.storeops.alerts.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long an SLA breach may go unresolved before it escalates.
 *
 * <p>Owned by the alerts module, because escalation is an alerts decision. The activities module
 * configures its own sweep cadence separately under {@code storeops.activities.sla} - neither module
 * sets the other's timing.
 *
 * <p>An absent or negative value falls back to two hours rather than failing startup. Negative is worth
 * substituting rather than obeying: it would make every second observation escalate on arrival, which
 * reaches a store manager as an escalation nobody was given the chance to prevent. A value that is
 * present but unparseable is still a startup failure, which is the right outcome for malformed config.
 *
 * @param gracePeriod time from the first observed breach before escalation is due
 */
@ConfigurationProperties("storeops.alerts.sla")
public record SlaEscalationProperties(Duration gracePeriod) {

    private static final Duration DEFAULT_GRACE_PERIOD = Duration.ofHours(2);

    public SlaEscalationProperties {
        gracePeriod = gracePeriod == null || gracePeriod.isNegative() ? DEFAULT_GRACE_PERIOD : gracePeriod;
    }
}
