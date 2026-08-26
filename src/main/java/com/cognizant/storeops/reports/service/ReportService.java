package com.cognizant.storeops.reports.service;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.dto.BlockedActivitySummary;
import com.cognizant.storeops.reports.dto.RegionalRollupResponse;
import com.cognizant.storeops.reports.dto.StoreRollupEntry;
import com.cognizant.storeops.reports.dto.StoreSummaryResponse;
import com.cognizant.storeops.reports.repository.ReportRepository;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.RegionalRollupRequestedEvent;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Attribution used when a rollup is requested without naming a staff member. No route in
     * StoreOps carries an authenticated principal yet, and {@code Report.requestedBy} still has to
     * say something truthful about where the request came from.
     */
    private static final String API_REQUESTER = "api";

    private final ReportRepository reportRepository;
    private final TaskService taskService;
    private final ProjectService projectService;
    private final UserService userService;
    private final EventBus eventBus;
    private final Clock clock;

    public ReportService(
            final ReportRepository reportRepository,
            final TaskService taskService,
            final ProjectService projectService,
            final UserService userService,
            final EventBus eventBus,
            final Clock clock) {
        this.reportRepository = reportRepository;
        this.taskService = taskService;
        this.projectService = projectService;
        this.userService = userService;
        this.eventBus = eventBus;
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
        final int completed = countWithStatus(tasks, TaskStatus.DONE);
        final int blocked = countWithStatus(tasks, TaskStatus.BLOCKED);
        final List<Task> overdue = tasks.stream().filter(task -> task.isOverdueAt(now)).toList();

        return new StoreSummaryResponse(
                storeId,
                now,
                total,
                completed,
                completionRate(completed, total),
                overdue.size(),
                blocked,
                countByStatus(tasks),
                countOverdueByCategory(overdue),
                (int) programmes.stream().filter(project -> !project.isClosed()).count(),
                userService.findByStoreId(storeId).size());
    }

    /**
     * Computes a regional rollup across every store in one region.
     *
     * <p>The store set comes from {@code UserService}: StoreOps has no {@code Store} entity, so the
     * staff roster's {@code region_id} is the only record of which stores a region contains. Each
     * store's activities are then read one store at a time through {@code TaskService}, and every
     * count is computed here in Java. A single query grouping activities by region would need either
     * a join across the {@code users} and {@code tasks} tables or a copy of the region id on
     * {@code TaskEntity}, and the StoreOps boundary rules forbid both.
     *
     * <p>A store in the region with no activities still appears in the breakdown, with a zero
     * completion rate rather than a missing row.
     *
     * <p>On success this publishes {@code REGIONAL_ROLLUP_REQUESTED}, which is what causes the
     * {@code REGIONAL_ROLLUP} report record to be written. {@code @Transactional} is load-bearing
     * and not decoration: subscribers run {@link org.springframework.transaction.event.TransactionPhase#AFTER_COMMIT},
     * and Spring skips after-commit callbacks outright when no transaction is active. A {@code GET}
     * has none by default, so without this annotation the event would be dropped with no exception
     * and no log line, and this method would still answer 200.
     *
     * @param regionId    region to roll up
     * @param requestedBy staff member asking, or null to attribute the request to {@code api}
     * @throws ValidationError when no region id is supplied
     * @throws NotFoundError   when {@code requestedBy} names no staff member, or when the region
     *                         contains no stores
     */
    @Transactional
    public RegionalRollupResponse regionalRollup(final String regionId, final String requestedBy) {
        if (regionId == null || regionId.isBlank()) {
            throw new ValidationError("A region id is required", List.of("regionId: must not be blank"));
        }
        final String requester = requestedBy == null || requestedBy.isBlank() ? API_REQUESTER : requestedBy;
        if (!API_REQUESTER.equals(requester) && !userService.exists(requester)) {
            throw NotFoundError.of("User", requester);
        }

        final List<String> storeIds = userService.findByRegionId(regionId).stream()
                .map(User::storeId)
                .filter(storeId -> storeId != null && !storeId.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (storeIds.isEmpty()) {
            throw NotFoundError.of("Region", regionId);
        }

        final Instant now = clock.instant();
        final List<Task> regionActivities = new ArrayList<>();
        final List<StoreRollupEntry> breakdown = new ArrayList<>();
        for (final String storeId : storeIds) {
            final List<Task> storeActivities = taskService.findByStoreId(storeId);
            regionActivities.addAll(storeActivities);
            breakdown.add(storeEntry(storeId, storeActivities, now));
        }

        final int total = regionActivities.size();
        final int completed = countWithStatus(regionActivities, TaskStatus.DONE);
        final List<Task> overdue = regionActivities.stream().filter(task -> task.isOverdueAt(now)).toList();

        final RegionalRollupResponse rollup = new RegionalRollupResponse(
                regionId,
                now,
                storeIds.size(),
                total,
                completed,
                completionRate(completed, total),
                overdue.size(),
                countWithStatus(regionActivities, TaskStatus.BLOCKED),
                countOverdueByCategoryWithZeroes(overdue),
                breakdown,
                blockedActivities(regionActivities));

        // Published last, after every validation has passed and the response exists. Dispatch is
        // after commit, so a thrown AppError would roll this back anyway, but ordering it here keeps
        // the "a failed rollup publishes nothing" contract true at the service level too, where the
        // test double has no transaction semantics to rescue it.
        eventBus.publish(new RegionalRollupRequestedEvent(regionId, requester, storeIds.size(), now));
        return rollup;
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

    private static StoreRollupEntry storeEntry(
            final String storeId, final List<Task> activities, final Instant now) {
        final int total = activities.size();
        final int completed = countWithStatus(activities, TaskStatus.DONE);
        return new StoreRollupEntry(
                storeId,
                total,
                completed,
                completionRate(completed, total),
                (int) activities.stream().filter(task -> task.isOverdueAt(now)).count(),
                countWithStatus(activities, TaskStatus.BLOCKED));
    }

    private static List<BlockedActivitySummary> blockedActivities(final List<Task> activities) {
        return activities.stream()
                .filter(task -> task.status() == TaskStatus.BLOCKED)
                .sorted(Comparator.comparing(Task::storeId, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Task::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(task -> new BlockedActivitySummary(
                        task.id(),
                        task.storeId(),
                        task.title(),
                        task.category() == null ? null : task.category().name(),
                        task.priority() == null ? null : task.priority().name(),
                        task.assigneeId()))
                .toList();
    }

    private static int countWithStatus(final List<Task> tasks, final TaskStatus status) {
        return (int) tasks.stream().filter(task -> task.status() == status).count();
    }

    private static double completionRate(final int completed, final int total) {
        return total == 0 ? 0.0 : round((double) completed / total);
    }

    /**
     * Overdue counts per category with every {@code TaskCategory} pre-seeded to zero.
     *
     * <p>Distinct from {@link #countOverdueByCategory}, which omits categories with nothing overdue.
     * A store summary covers one store and reads naturally as a sparse map; a regional rollup is
     * compared across regions and across runs, so a stable set of keys matters more.
     */
    private static Map<String, Integer> countOverdueByCategoryWithZeroes(final List<Task> overdue) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final TaskCategory category : TaskCategory.values()) {
            counts.put(category.name(), 0);
        }
        counts.putAll(countOverdueByCategory(overdue));
        return counts;
    }

    private static Map<String, Integer> countByStatus(final List<Task> tasks) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final TaskStatus status : TaskStatus.values()) {
            counts.put(status.name(), countWithStatus(tasks, status));
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
