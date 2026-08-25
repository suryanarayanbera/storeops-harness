package com.cognizant.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the SLA sweep trigger.
 *
 * <p>{@code TaskService} is mocked rather than faked here, which is the one place in this sprint
 * where that is the right call: the assertion is about delegation, not about which activities
 * breached. Whether the service picks the right activities is settled by {@code TaskServiceTest},
 * and whether Spring actually invokes this method on a schedule is settled by
 * {@code SlaSweepWiringTest}.
 */
class SlaSweepSchedulerTest {

    private TaskService taskService;
    private SlaSweepScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        scheduler = new SlaSweepScheduler(taskService);
    }

    @Test
    @DisplayName("the sweep delegates to the service exactly once and does nothing else")
    void sweepDelegatesToTheService() {
        when(taskService.publishOverdueBreaches()).thenReturn(3);

        scheduler.sweepOverdueActivities();

        verify(taskService).publishOverdueBreaches();
        // No repository access, no second call, no filtering of its own: the trigger holds no rules.
        verifyNoMoreInteractions(taskService);
    }

    @Test
    @DisplayName("the sweep completes quietly when nothing has breached")
    void sweepToleratesAnEmptyResult() {
        when(taskService.publishOverdueBreaches()).thenReturn(0);

        assertThatCode(() -> scheduler.sweepOverdueActivities()).doesNotThrowAnyException();

        verify(taskService).publishOverdueBreaches();
    }

    @Test
    @DisplayName("each scheduled cycle triggers a fresh sweep")
    void everyCycleSweepsAgain() {
        when(taskService.publishOverdueBreaches()).thenReturn(1);

        scheduler.sweepOverdueActivities();
        scheduler.sweepOverdueActivities();

        verify(taskService, times(2)).publishOverdueBreaches();
    }
}
