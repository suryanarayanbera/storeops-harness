package com.cognizant.storeops.alerts.repository;

import com.cognizant.storeops.alerts.domain.SlaBreach;
import java.util.Optional;

/**
 * Data access for SLA breach episodes. Owned by the alerts module.
 *
 * <p>No other module may import this interface. The activities module publishes the breach; whether it
 * has been alerted on, and to whom, is nobody else's business. Enforced by {@code ModuleBoundaryTest}.
 */
public interface SlaBreachRepository {

    SlaBreach save(SlaBreach breach);

    /** The open episode for an activity, if one exists. The activity id is the episode's identity. */
    Optional<SlaBreach> findByTaskId(String taskId);

    /**
     * Ends an episode, returning whether there was one to end.
     *
     * <p>Deletion rather than a resolved flag: a closed episode has nothing left to decide, and keeping
     * the row would leave a stale {@code firstBreachAt} for a reopened activity to escalate against.
     */
    boolean deleteByTaskId(String taskId);
}
