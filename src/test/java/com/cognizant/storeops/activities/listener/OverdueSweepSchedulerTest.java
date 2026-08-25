package com.cognizant.storeops.activities.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cognizant.storeops.activities.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sweep trigger's only job is to call the service on a timer.
 *
 * <p>{@code TaskService} is mocked rather than faked because what is under test is the delegation
 * itself: that the scheduler asks the service and decides nothing. Whether the service then picks the
 * right activities is {@code TaskServiceTest}'s question, and whether Spring fires the timer at all is
 * Spring's.
 */
class OverdueSweepSchedulerTest {

    private TaskService taskService;
    private OverdueSweepScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        scheduler = new OverdueSweepScheduler(taskService);
    }

    @Test
    @DisplayName("a sweep asks the service to publish breaches exactly once, and does nothing else")
    void sweepDelegatesToTheService() {
        when(taskService.publishOverdueBreaches()).thenReturn(2);

        scheduler.sweepForOverdueBreaches();

        verify(taskService).publishOverdueBreaches();
        // No due-date arithmetic, no priority check, no event of its own: the scheduler is an
        // inbound adapter, and every rule belongs to the service.
        verifyNoMoreInteractions(taskService);
    }

    @Test
    @DisplayName("a sweep that finds no breach is an ordinary outcome, not an error")
    void sweepWithNothingOverdueIsSilent() {
        when(taskService.publishOverdueBreaches()).thenReturn(0);

        scheduler.sweepForOverdueBreaches();
        scheduler.sweepForOverdueBreaches();

        verify(taskService, times(2)).publishOverdueBreaches();
        verifyNoMoreInteractions(taskService);
    }
}
