package com.cognizant.storeops.programmes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectMember;
import com.cognizant.storeops.programmes.domain.ProjectRole;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.InMemoryEventBus;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Service-layer test for the programmes module. */
class ProjectServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private FakeProjectRepository projectRepository;
    private UserService userService;
    private InMemoryEventBus eventBus;
    private ProjectService projectService;
    private List<ProgrammeClosedEvent> closedEvents;

    @BeforeEach
    void setUp() {
        projectRepository = new FakeProjectRepository();
        userService = mock(UserService.class);
        eventBus = new InMemoryEventBus();
        projectService = new ProjectService(projectRepository, userService, eventBus,
                Clock.fixed(NOW, ZoneOffset.UTC));

        closedEvents = new ArrayList<>();
        eventBus.subscribe(ProgrammeClosedEvent.class, closedEvents::add);

        when(userService.exists("user-002")).thenReturn(true);
    }

    private CreateProjectRequest createRequest() {
        return new CreateProjectRequest("Spring seasonal rollout", "Aisle resets",
                "store-001", "region-north", "user-002");
    }

    private Project seedProject(final String id, final ProjectStatus status) {
        return projectRepository.save(new Project(id, "Seeded " + id, null, status,
                "store-001", "region-north", "user-002",
                List.of(new ProjectMember("user-002", ProjectRole.STORE_MANAGER, NOW)), NOW, null));
    }

    @Test
    @DisplayName("create opens the programme as PLANNED with the owner enrolled as STORE_MANAGER")
    void createEnrollsOwner() {
        final Project created = projectService.create(createRequest());

        assertThat(created.status()).isEqualTo(ProjectStatus.PLANNED);
        assertThat(created.closedAt()).isNull();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.members()).hasSize(1);
        assertThat(created.members().getFirst().userId()).isEqualTo("user-002");
        assertThat(created.members().getFirst().role()).isEqualTo(ProjectRole.STORE_MANAGER);
    }

    @Test
    @DisplayName("create rejects an owner the staff module does not know")
    void createRejectsUnknownOwner() {
        final CreateProjectRequest request = new CreateProjectRequest("Refit", null,
                "store-001", "region-north", "user-999");

        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> projectService.create(request))
                .satisfies(error -> assertThat(error.getDetails()).hasSize(1));
        assertThat(projectRepository.count()).isZero();
    }

    @Test
    @DisplayName("getById raises a typed NotFoundError for an unknown id")
    void getByIdRaisesTypedNotFound() {
        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> projectService.getById("nope"))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("PROJECT_NOT_FOUND");
                    assertThat(error.getStatusCode()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("close moves the programme to CLOSED and publishes ProgrammeClosedEvent")
    void closePublishesEvent() {
        seedProject("project-001", ProjectStatus.ACTIVE);

        final Project closed = projectService.close("project-001", "user-002");

        assertThat(closed.status()).isEqualTo(ProjectStatus.CLOSED);
        assertThat(closed.closedAt()).isEqualTo(NOW);
        assertThat(closedEvents).hasSize(1);
        assertThat(closedEvents.getFirst().projectId()).isEqualTo("project-001");
        assertThat(closedEvents.getFirst().storeId()).isEqualTo("store-001");
        assertThat(closedEvents.getFirst().closedByUserId()).isEqualTo("user-002");
        assertThat(closedEvents.getFirst().eventType()).isEqualTo("PROGRAMME_CLOSED");
    }

    @Test
    @DisplayName("close refuses an already closed programme and publishes nothing")
    void closeRefusesAlreadyClosed() {
        seedProject("project-001", ProjectStatus.CLOSED);

        assertThatExceptionOfType(ConflictError.class)
                .isThrownBy(() -> projectService.close("project-001", "user-002"))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("PROGRAMME_ALREADY_CLOSED");
                    assertThat(error.getStatusCode()).isEqualTo(409);
                });
        assertThat(closedEvents).isEmpty();
    }

    @Test
    @DisplayName("list filters by status and store")
    void listFilters() {
        seedProject("project-001", ProjectStatus.ACTIVE);
        seedProject("project-002", ProjectStatus.PLANNED);

        assertThat(projectService.list(null, null)).hasSize(2);
        assertThat(projectService.list(ProjectStatus.ACTIVE, null))
                .extracting(Project::id).containsExactly("project-001");
        assertThat(projectService.list(null, "store-999")).isEmpty();
        assertThat(projectService.list(ProjectStatus.ACTIVE, "store-999")).isEmpty();
    }
}
