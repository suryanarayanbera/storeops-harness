package com.cognizant.storeops.programmes.service;

import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectMember;
import com.cognizant.storeops.programmes.domain.ProjectRole;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.programmes.repository.ProjectRepository;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

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
     * @throws NotFoundError when no programme has that id
     * @throws ConflictError when the programme is already closed
     */
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

    /** Read-only view for the reports module. */
    public List<Project> findByStoreId(final String storeId) {
        return projectRepository.findByStoreId(storeId);
    }

    /** Read-only view for the reports module. */
    public List<Project> findByRegionId(final String regionId) {
        return projectRepository.findByRegionId(regionId);
    }
}
