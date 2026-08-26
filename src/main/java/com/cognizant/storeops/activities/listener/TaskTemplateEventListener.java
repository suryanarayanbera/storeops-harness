package com.cognizant.storeops.activities.listener;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.shared.events.ProgrammeTemplateRequestedEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The activities module's inbound edge.
 *
 * <p>Applying a task template to a programme should raise activities. The programmes module cannot do
 * that - {@code Task} belongs here, and a direct write across the boundary is banned - so it publishes
 * {@code PROGRAMME_TEMPLATE_REQUESTED} and this listener decides what work follows. The rows land in
 * this module's own table, which is what makes the whole feature legal.
 *
 * <p>Note what this class does <em>not</em> import: nothing from {@code programmes}. The event arrives
 * carrying activities that have already been resolved - titles, priorities and assignees settled by
 * the publisher - so there is no template catalogue to read here and no programme membership to
 * inspect. An import in this direction, alongside the publication in the other, would be the
 * dependency cycle {@code ModuleBoundaryTest} rule 2 fails on. Resolving before publishing is what
 * buys both modules their independence.
 *
 * <p>Runs {@link TransactionPhase#AFTER_COMMIT}, so a programme whose template application rolled
 * back raises nothing. {@link Propagation#REQUIRES_NEW} is required, not optional: at after-commit
 * time the publishing transaction has already committed, so a write joining it is never flushed. The
 * listener would run, the log line below would print, and no activity would exist - which is exactly
 * the failure {@code PlanogramTemplateDeliveryIntegrationTest} exists to catch.
 */
@Component
public class TaskTemplateEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(TaskTemplateEventListener.class);

    private final TaskService taskService;

    public TaskTemplateEventListener(final TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * Creates one activity per carried item, skipping the ones already on the programme.
     *
     * <p>The skip is why the created count is logged against the carried count rather than on its own:
     * a repeat application is a legitimate no-op, and "created 0 of 4" is the line that says so
     * without looking like a failure.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProgrammeTemplateRequested(final ProgrammeTemplateRequestedEvent event) {
        final List<Task> created =
                taskService.createFromTemplate(event.projectId(), event.storeId(), event.items());
        LOG.info("Created {} of {} activities from template {} for programme {}, requested by {}",
                created.size(), event.items().size(), event.templateId(), event.projectId(),
                event.requestedBy());
    }
}
