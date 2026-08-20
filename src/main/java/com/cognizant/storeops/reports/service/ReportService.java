package com.cognizant.storeops.reports.service;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.dto.StoreSummaryResponse;
import com.cognizant.storeops.reports.repository.ReportRepository;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Aggregation logic for store and regional metrics.
 *
 * <p>StoreOps architecture rule: <em>the reports module is read-only</em>. It reads activities,
 * programmes and staff through their service layers and writes to nothing except its own
 * {@link ReportRepository}. No setter, no save, no status transition on another module's data
 * appears here, and {@code ModuleBoundaryTest} fails the build if one is ever added.
 */
@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final TaskService taskService;
    private final ProjectService projectService;
    private final UserService userService;
    private final Clock clock;

    public ReportService(
            final ReportRepository reportRepository,
            final TaskService taskService,
            final ProjectService projectService,
            final UserService userService,
            final Clock clock) {
        this.reportRepository = reportRepository;
        this.taskService = taskService;
        this.projectService = projectService;
        this.userService = userService;
        this.clock = clock;
    }

    /**
     * Endpoint 9 backing call: computes a store summary on demand.
     *
     * @throws ValidationError when no store id is supplied
     */
    public StoreSummaryResponse storeSummary(final String storeId) {
        if (storeId == null || storeId.isBlank()) {
            throw new ValidationError("A store id is required", List.of("storeId: must not be blank"));
        }
        final Instant now = clock.instant();
        final List<Task> tasks = taskService.findByStoreId(storeId);
        final List<Project> programmes = projectService.findByStoreId(storeId);

        final int total = tasks.size();
        final int completed = (int) tasks.stream().filter(task -> task.status() == TaskStatus.DONE).count();
        final int blocked = (int) tasks.stream().filter(task -> task.status() == TaskStatus.BLOCKED).count();
        final List<Task> overdue = tasks.stream().filter(task -> task.isOverdueAt(now)).toList();

        return new StoreSummaryResponse(
                storeId,
                now,
                total,
                completed,
                total == 0 ? 0.0 : round((double) completed / total),
                overdue.size(),
                blocked,
                countByStatus(tasks),
                countOverdueByCategory(overdue),
                (int) programmes.stream().filter(project -> !project.isClosed()).count(),
                userService.findByStoreId(storeId).size());
    }

    /**
     * Records a report request in PENDING state.
     *
     * <p>Called by {@code ReportEventListener} when a programme closes, and available to future
     * report endpoints. This is a write to the reports module's own store only.
     */
    public Report queue(final ReportType reportType, final String scopeId, final String requestedBy) {
        final Report report = new Report(
                UUID.randomUUID().toString(),
                reportType,
                ReportStatus.PENDING,
                scopeId,
                requestedBy,
                clock.instant(),
                null);
        return reportRepository.save(report);
    }

    /**
     * Marks a queued report ready.
     *
     * <p>Stub: no generation pipeline exists yet, so this is the seam a future worker would call.
     *
     * @throws NotFoundError when no report has that id
     */
    public Report markReady(final String reportId) {
        final Report existing = reportRepository.findById(reportId)
                .orElseThrow(() -> NotFoundError.of("Report", reportId));
        return reportRepository.save(existing.withStatus(ReportStatus.READY, clock.instant()));
    }

    /** Report records raised for one store or region. */
    public List<Report> findByScopeId(final String scopeId) {
        return reportRepository.findByScopeId(scopeId);
    }

    private static Map<String, Integer> countByStatus(final List<Task> tasks) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final TaskStatus status : TaskStatus.values()) {
            counts.put(status.name(), (int) tasks.stream().filter(task -> task.status() == status).count());
        }
        return counts;
    }

    private static Map<String, Integer> countOverdueByCategory(final List<Task> overdue) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final Task task : overdue) {
            final String category = task.category() == null ? "UNCATEGORISED" : task.category().name();
            counts.merge(category, 1, Integer::sum);
        }
        return counts;
    }

    private static double round(final double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
