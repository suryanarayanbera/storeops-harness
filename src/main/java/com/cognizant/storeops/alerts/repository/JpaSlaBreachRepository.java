package com.cognizant.storeops.alerts.repository;

import com.cognizant.storeops.alerts.domain.SlaBreach;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** H2-backed {@link SlaBreachRepository}. */
@Repository
public class JpaSlaBreachRepository implements SlaBreachRepository {

    private final SlaBreachJpaRepository breaches;

    JpaSlaBreachRepository(final SlaBreachJpaRepository breaches) {
        this.breaches = breaches;
    }

    @Override
    public SlaBreach save(final SlaBreach breach) {
        return breaches.save(SlaBreachEntity.fromDomain(breach)).toDomain();
    }

    @Override
    public Optional<SlaBreach> findByTaskId(final String taskId) {
        return taskId == null ? Optional.empty() : breaches.findById(taskId).map(SlaBreachEntity::toDomain);
    }

    @Override
    public boolean deleteByTaskId(final String taskId) {
        if (taskId == null || !breaches.existsById(taskId)) {
            return false;
        }
        breaches.deleteById(taskId);
        return true;
    }
}
