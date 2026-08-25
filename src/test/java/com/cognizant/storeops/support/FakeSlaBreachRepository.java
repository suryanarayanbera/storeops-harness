package com.cognizant.storeops.support;

import com.cognizant.storeops.alerts.domain.SlaBreach;
import com.cognizant.storeops.alerts.repository.SlaBreachRepository;
import java.util.Optional;

/** Test double for {@link SlaBreachRepository}. Starts empty; tests build the state they need. */
public class FakeSlaBreachRepository extends FakeRepository<SlaBreach, String>
        implements SlaBreachRepository {

    public FakeSlaBreachRepository() {
        super(SlaBreach::taskId);
    }

    @Override
    public Optional<SlaBreach> findByTaskId(final String taskId) {
        return findById(taskId);
    }

    @Override
    public boolean deleteByTaskId(final String taskId) {
        return deleteById(taskId);
    }
}
