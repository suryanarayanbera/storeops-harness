package com.cognizant.storeops.alerts.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * One SLA breach episode: an activity that missed its deadline, and what the alerts module has already
 * done about it. Immutable; updates go through the {@code with*} copy methods.
 *
 * <p>This record is the alerts module's memory, and the reason a breach observed on every sweep raises
 * exactly one alert. The activities module republishes {@code TaskOverdueEvent} for as long as an
 * activity stays overdue - deliberately, because the repeat is what tells alerts the breach is still
 * unresolved - so de-duplication has to live on this side of the boundary and has to be durable. A flag
 * held in a service field would forget everything on restart and re-alert.
 *
 * <p>An episode ends when the activity is resolved. There is no {@code resolved} flag: the row is
 * deleted, so a later reopening starts a fresh episode rather than resuming a stale one.
 *
 * @param taskId                activity that breached, and the episode's identity - one open episode per activity
 * @param storeId               store the activity belongs to, copied off the event
 * @param priority              {@code TaskPriority} name, copied off the event
 * @param firstBreachAt         when the breach was first observed; the grace period is measured from here
 * @param leadRecipientId       staff member who received the {@code SLA_BREACH} alert
 * @param leadNotifiedAt        when that alert was raised
 * @param lastSeenAt            most recent observation; diagnostic, and settles no decision
 * @param escalationRecipientId store manager the breach escalated to, null until it escalates
 * @param escalatedAt           when it escalated, null until then - and the reason it escalates only once
 */
public record SlaBreach(
        String taskId,
        String storeId,
        String priority,
        Instant firstBreachAt,
        String leadRecipientId,
        Instant leadNotifiedAt,
        Instant lastSeenAt,
        String escalationRecipientId,
        Instant escalatedAt) {

    /** Opens an episode: the breach has just been seen for the first time and the lead has been told. */
    public static SlaBreach opened(
            final String taskId,
            final String storeId,
            final String priority,
            final String leadRecipientId,
            final Instant observedAt) {
        return new SlaBreach(taskId, storeId, priority, observedAt, leadRecipientId, observedAt, observedAt,
                null, null);
    }

    /** True once the breach has escalated, whether or not a second alert was raised for it. */
    public boolean isEscalated() {
        return escalatedAt != null;
    }

    /** The moment escalation becomes due, given how long a breach is allowed to go unresolved. */
    public Instant escalationDueAt(final Duration gracePeriod) {
        return firstBreachAt.plus(gracePeriod);
    }

    public SlaBreach withLastSeen(final Instant seenAt) {
        return new SlaBreach(taskId, storeId, priority, firstBreachAt, leadRecipientId, leadNotifiedAt,
                seenAt, escalationRecipientId, escalatedAt);
    }

    /** Records that the breach escalated. Also counts as an observation, so {@code lastSeenAt} moves. */
    public SlaBreach withEscalation(final String recipientId, final Instant escalatedTo) {
        return new SlaBreach(taskId, storeId, priority, firstBreachAt, leadRecipientId, leadNotifiedAt,
                escalatedTo, recipientId, escalatedTo);
    }
}
