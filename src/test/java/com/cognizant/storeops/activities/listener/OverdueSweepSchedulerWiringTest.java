package com.cognizant.storeops.activities.listener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The sweep is wired by default.
 *
 * <p>The companion {@code OverdueSweepSchedulerDisabledTest} proves the off switch. Both are needed:
 * a scheduler that is only ever asserted absent would pass while the feature never ran, and one that
 * cannot be switched off leaves integration tests at the mercy of a background timer.
 */
@SpringBootTest
class OverdueSweepSchedulerWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the overdue sweep is registered when the enabling property is absent")
    void schedulerIsPresentByDefault() {
        assertThat(context.getBeansOfType(OverdueSweepScheduler.class)).hasSize(1);
    }
}
