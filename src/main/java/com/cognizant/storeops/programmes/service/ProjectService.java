package com.cognizant.storeops.programmes.service;

import com.cognizant.storeops.programmes.domain.PlanogramTemplate;
import com.cognizant.storeops.programmes.domain.PlanogramTemplateItem;
import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectMember;
import com.cognizant.storeops.programmes.domain.ProjectRole;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.dto.ApplyTemplateRequest;
import com.cognizant.storeops.programmes.dto.ApplyTemplateResponse;
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.programmes.dto.TemplateAssignmentResponse;
import com.cognizant.storeops.programmes.repository.ProjectRepository;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.shared.events.ProgrammeTemplateRequestedEvent;
import com.cognizant.storeops.shared.events.TemplateTaskDefinition;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for store programmes and their staff membership.
 *
 * <p>Owner and member ids are validated against the staff module through {@link UserService} - a
 * read-only service-layer lookup. Closing a programme publishes {@link ProgrammeClosedEvent}; this
 * class has no idea that the reports module reacts to it.
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserService userService;
    private final EventBus eventBus;
    private final Clock clock;

    public ProjectService(
            final ProjectRepository projectRepository,
            final UserService userService,
            final EventBus eventBus,
            final Clock clock) {
        this.projectRepository = projectRepository;
        this.userService = userService;
        this.eventBus = eventBus;
        this.clock = clock;
    }

    /** Endpoint 5 backing call. */
    public List<Project> list(final ProjectStatus status, final String storeId) {
        if (status != null) {
            return projectRepository.findByStatus(status).stream()
                    .filter(project -> storeId == null || storeId.equals(project.storeId()))
                    .toList();
        }
        return storeId == null ? projectRepository.findAll() : projectRepository.findByStoreId(storeId);
    }

    /**
     * Loads one programme.
     *
     * @throws NotFoundError when no programme has that id
     */
    public Project getById(final String id) {
        return projectRepository.findById(id).orElseThrow(() -> NotFoundError.of("Project", id));
    }

    /**
     * Creates a programme in PLANNED state with the owner enrolled as STORE_MANAGER.
     *
     * @throws ValidationError when the named owner is not a known staff member
     */
    public Project create(final CreateProjectRequest request) {
        if (!userService.exists(request.ownerId())) {
            throw new ValidationError(
                    "Owner is not a known staff member",
                    List.of("ownerId: unknown staff member '" + request.ownerId() + "'"));
        }
        final Instant now = clock.instant();
        final Project project = new Project(
                UUID.randomUUID().toString(),
                request.name().trim(),
                request.description(),
                ProjectStatus.PLANNED,
                request.storeId(),
                request.regionId(),
                request.ownerId(),
                List.of(new ProjectMember(request.ownerId(), ProjectRole.STORE_MANAGER, now)),
                now,
                null);
        return projectRepository.save(project);
    }

    /**
     * Closes a programme and publishes {@link ProgrammeClosedEvent}.
     *
     * <p>Stub: reached from {@link #close(String, String)} only, no endpoint yet.
     *
     * <p>Transactional so the published event reaches its after-commit subscribers; without a
     * transaction the reports module would never queue the summary.
     *
     * @throws NotFoundError when no programme has that id
     * @throws ConflictError when the programme is already closed
     */
    @Transactional
    public Project close(final String id, final String closedByUserId) {
        final Project existing = getById(id);
        if (existing.isClosed()) {
            throw new ConflictError("PROGRAMME_ALREADY_CLOSED", "Programme '" + id + "' is already closed");
        }
        final Instant now = clock.instant();
        final Project closed = projectRepository.save(existing.withStatus(ProjectStatus.CLOSED, now));
        eventBus.publish(new ProgrammeClosedEvent(closed.id(), closed.storeId(), closedByUserId, now));
        return closed;
    }

    /**
     * Applies a task template to a programme and publishes
     * {@link ProgrammeTemplateRequestedEvent} carrying the resolved activities.
     *
     * <p>This method creates no activity, and cannot: activities belong to the {@code activities}
     * module and a direct write into another module's store is forbidden. It does the part that needs
     * programme knowledge - validate, expand the catalogue, resolve each line's department to a
     * member - and publishes the result. The {@code activities} module creates the rows on the far
     * side of the bus, which is why the endpoint answers {@code 202} rather than {@code 201}.
     *
     * <p>Transactional so the published event reaches its after-commit subscriber. Without the
     * annotation Spring skips the callback entirely and no activity is ever created, with nothing
     * logged and no test using {@code RecordingEventBus} able to tell - that double records at
     * publish time.
     *
     * @throws NotFoundError   when no programme has that id
     * @throws ConflictError   when the programme is closed
     * @throws ValidationError when the template id or the requester is not known
     */
    @Transactional
    public ApplyTemplateResponse applyTemplate(final String id, final ApplyTemplateRequest request) {
        final Project project = getById(id);
        if (project.isClosed()) {
            throw new ConflictError(
                    "PROGRAMME_CLOSED",
                    "Programme '" + id + "' is closed; activities cannot be added to it");
        }
        final PlanogramTemplate template = PlanogramTemplate.findById(request.templateId())
                .orElseThrow(() -> new ValidationError(
                        "Template is not a known task template",
                        List.of("templateId: unknown template '" + request.templateId()
                                + "', known templates are " + PlanogramTemplate.knownIds())));
        final String requestedBy = resolveRequestedBy(request.requestedBy());

        final List<ResolvedItem> resolved = template.items().stream()
                .map(item -> new ResolvedItem(item, resolveAssignee(project, item.department())))
                .toList();

        eventBus.publish(new ProgrammeTemplateRequestedEvent(
                project.id(),
                project.storeId(),
                template.templateId(),
                requestedBy,
                resolved.stream().map(ResolvedItem::toDefinition).toList(),
                clock.instant()));

        return new ApplyTemplateResponse(
                project.id(),
                template.templateId(),
                resolved.size(),
                resolved.stream().map(ResolvedItem::toAssignment).toList());
    }

    /**
     * Defaults an absent requester to {@code api}, matching {@code RegionalRollupRequestedEvent}, and
     * validates a stated one against the staff roster.
     */
    private String resolveRequestedBy(final String requestedBy) {
        if (requestedBy == null || requestedBy.isBlank()) {
            return "api";
        }
        final String stated = requestedBy.trim();
        if (!userService.exists(stated)) {
            throw new ValidationError(
                    "Requester is not a known staff member",
                    List.of("requestedBy: unknown staff member '" + stated + "'"));
        }
        return stated;
    }

    /**
     * Picks the programme member who should own a template line, by department.
     *
     * <p>Candidates are this programme's members only - a department lead at the store who is not on
     * the programme is not eligible, because the template is being applied to the programme rather
     * than to the store. Leavers are excluded here rather than in {@link UserService#findById}, which
     * deliberately returns them.
     *
     * <p>A {@code DEPARTMENT_LEAD} wins over anyone else in the same department; ties break on the
     * lowest staff id so two equally valid candidates do not make the outcome depend on member
     * ordering. Returns null when no member covers the department, which is a reportable outcome
     * rather than an error - see {@code TemplateAssignmentResponse}.
     */
    private String resolveAssignee(final Project project, final String department) {
        return project.members().stream()
                .flatMap(member -> userService.findById(member.userId())
                        .filter(User::active)
                        .filter(user -> worksIn(user, department))
                        .map(user -> new Candidate(member.role(), user.id()))
                        .stream())
                .min(Comparator.comparingInt((Candidate candidate) -> candidate.rank())
                        .thenComparing(Candidate::userId))
                .map(Candidate::userId)
                .orElse(null);
    }

    private static boolean worksIn(final User user, final String department) {
        return department != null && department.equalsIgnoreCase(user.profile().department());
    }

    /** A template line with its department resolved to a member, or to nobody. */
    private record ResolvedItem(PlanogramTemplateItem item, String assigneeId) {

        TemplateTaskDefinition toDefinition() {
            return new TemplateTaskDefinition(
                    item.title(), item.description(), PlanogramTemplate.CATEGORY, item.priority(), assigneeId);
        }

        TemplateAssignmentResponse toAssignment() {
            return new TemplateAssignmentResponse(
                    item.title(), item.department(), item.priority(), assigneeId);
        }
    }

    /** An eligible member, ranked so that a department lead sorts ahead of everyone else. */
    private record Candidate(ProjectRole role, String userId) {

        int rank() {
            return role == ProjectRole.DEPARTMENT_LEAD ? 0 : 1;
        }
    }

    /** Read-only view for the reports module. */
    public List<Project> findByStoreId(final String storeId) {
        return projectRepository.findByStoreId(storeId);
    }

    /** Read-only view for the reports module. */
    public List<Project> findByRegionId(final String regionId) {
        return projectRepository.findByRegionId(regionId);
    }
}
