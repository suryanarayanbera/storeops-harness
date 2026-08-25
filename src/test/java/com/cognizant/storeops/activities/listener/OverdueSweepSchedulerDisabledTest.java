package com.cognizant.storeops.activities.listener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The sweep's off switch.
 *
 * <p>This is how a test that must not see a background sweep removes it outright, rather than relying
 * on the initial delay outrunning the test. The property is the documented way to disable the feature
 * in an environment that should not alert.
 */
@SpringBootTest
@TestPropertySource(properties = "storeops.activities.sla.sweep.enabled=false")
class OverdueSweepSchedulerDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the overdue sweep is not registered when its enabling property is false")
    void schedulerIsAbsentWhenDisabled() {
        assertThat(context.getBeansOfType(OverdueSweepScheduler.class)).isEmpty();
    }
}
