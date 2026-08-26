package com.cognizant.storeops.programmes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectMember;
import com.cognizant.storeops.programmes.domain.ProjectRole;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.dto.ApplyTemplateRequest;
import com.cognizant.storeops.programmes.dto.ApplyTemplateResponse;
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.programmes.dto.TemplateAssignmentResponse;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.shared.events.ProgrammeTemplateRequestedEvent;
import com.cognizant.storeops.shared.events.TemplateTaskDefinition;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeProjectRepository;
import com.cognizant.storeops.support.FakeUserRepository;
import com.cognizant.storeops.support.RecordingEventBus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Service-layer test for the programmes module. */
class ProjectServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private static final String ENTRANCE_BAY = "Reset entrance promotional bay";
    private static final String GROCERY_AISLES = "Reset grocery aisle planograms";
    private static final String SHELF_LABELS = "Verify shelf-edge labelling";
    private static final String PHOTOGRAPH_BAYS = "Photograph completed bays for compliance";

    private FakeProjectRepository projectRepository;
    private FakeUserRepository userRepository;
    private RecordingEventBus eventBus;
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectRepository = new FakeProjectRepository();
        // A real UserService over the roster fake rather than a Mockito mock. The department
        // resolution below reads a member's profile, and stubbing findById per member per test would
        // put the fixture's shape in the way of the rule being tested.
        userRepository = new FakeUserRepository().withSeedRoster();
        eventBus = new RecordingEventBus();
        projectService = new ProjectService(projectRepository, new UserService(userRepository), eventBus,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private List<ProgrammeClosedEvent> closedEvents() {
        return eventBus.published(ProgrammeClosedEvent.class);
    }

    private List<ProgrammeTemplateRequestedEvent> templateEvents() {
        return eventBus.published(ProgrammeTemplateRequestedEvent.class);
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

    /** A programme with an explicit membership, so a test can state exactly who is on it. */
    private Project seedProject(
            final String id, final ProjectStatus status, final String storeId, final ProjectMember... members) {
        return projectRepository.save(new Project(id, "Seeded " + id, null, status,
                storeId, "region-north", "user-002", List.of(members), NOW, null));
    }

    private static ProjectMember member(final String userId, final ProjectRole role) {
        return new ProjectMember(userId, role, NOW);
    }

    private static ApplyTemplateRequest standardTemplate(final String requestedBy) {
        return new ApplyTemplateRequest("PLANOGRAM_STANDARD", requestedBy);
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
        assertThat(closedEvents()).hasSize(1);
        assertThat(closedEvents().getFirst().projectId()).isEqualTo("project-001");
        assertThat(closedEvents().getFirst().storeId()).isEqualTo("store-001");
        assertThat(closedEvents().getFirst().closedByUserId()).isEqualTo("user-002");
        assertThat(closedEvents().getFirst().eventType()).isEqualTo("PROGRAMME_CLOSED");
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
        assertThat(closedEvents()).isEmpty();
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

    // ------------------------------------------------------------------ applyTemplate

    @Test
    @DisplayName("applyTemplate resolves the departments it can cover and leaves the rest unassigned")
    void applyTemplateResolvesCoveredDepartments() {
        seedProject("project-002", ProjectStatus.PLANNED, "store-002",
                member("user-005", ProjectRole.STORE_MANAGER));

        final ApplyTemplateResponse response = projectService.applyTemplate("project-002", standardTemplate("user-005"));

        assertThat(response.projectId()).isEqualTo("project-002");
        assertThat(response.templateId()).isEqualTo("PLANOGRAM_STANDARD");
        assertThat(response.taskCount()).isEqualTo(4);
        // user-005 is the only member and works in OPERATIONS, so the two GROCERY lines find nobody.
        assertThat(response.assignments())
                .extracting(TemplateAssignmentResponse::title, TemplateAssignmentResponse::department,
                        TemplateAssignmentResponse::priority, TemplateAssignmentResponse::assigneeId)
                .containsExactly(
                        tuple(ENTRANCE_BAY, "OPERATIONS", "HIGH", "user-005"),
                        tuple(GROCERY_AISLES, "GROCERY", "HIGH", null),
                        tuple(SHELF_LABELS, "GROCERY", "MEDIUM", null),
                        tuple(PHOTOGRAPH_BAYS, "OPERATIONS", "LOW", "user-005"));
    }

    @Test
    @DisplayName("applyTemplate publishes one PROGRAMME_TEMPLATE_REQUESTED carrying the resolved lines")
    void applyTemplatePublishesResolvedLines() {
        seedProject("project-002", ProjectStatus.PLANNED, "store-002",
                member("user-005", ProjectRole.STORE_MANAGER));

        projectService.applyTemplate("project-002", standardTemplate("user-005"));

        assertThat(templateEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("PROGRAMME_TEMPLATE_REQUESTED");
            assertThat(event.projectId()).isEqualTo("project-002");
            assertThat(event.storeId()).isEqualTo("store-002");
            assertThat(event.templateId()).isEqualTo("PLANOGRAM_STANDARD");
            assertThat(event.requestedBy()).isEqualTo("user-005");
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.items()).hasSize(4);
            // Every line is a planogram activity, and the category travels as a String so that
            // shared never depends on activities.domain.
            assertThat(event.items()).allSatisfy(item ->
                    assertThat(item.category()).isEqualTo("PLANOGRAM"));
            assertThat(event.items()).extracting(TemplateTaskDefinition::priority)
                    .containsExactly("HIGH", "HIGH", "MEDIUM", "LOW");
            assertThat(event.items()).extracting(TemplateTaskDefinition::title)
                    .containsExactly(ENTRANCE_BAY, GROCERY_AISLES, SHELF_LABELS, PHOTOGRAPH_BAYS);
            assertThat(event.items()).extracting(TemplateTaskDefinition::assigneeId)
                    .containsExactly("user-005", null, null, "user-005");
            assertThat(event.items()).allSatisfy(item ->
                    assertThat(item.description()).isNotBlank());
        });
    }

    @Test
    @DisplayName("applyTemplate prefers a DEPARTMENT_LEAD over an ASSOCIATE in the same department")
    void applyTemplatePrefersDepartmentLead() {
        // user-003 (GROCERY) leads the department; user-004 is also GROCERY but an associate.
        seedProject("project-001", ProjectStatus.ACTIVE, "store-001",
                member("user-002", ProjectRole.STORE_MANAGER),
                member("user-003", ProjectRole.DEPARTMENT_LEAD),
                member("user-004", ProjectRole.ASSOCIATE));

        final ApplyTemplateResponse response = projectService.applyTemplate("project-001", standardTemplate(null));

        assertThat(response.assignments()).extracting(TemplateAssignmentResponse::assigneeId)
                .containsExactly("user-002", "user-003", "user-003", "user-002");
        assertThat(templateEvents()).singleElement()
                .satisfies(event -> assertThat(event.items()).extracting(TemplateTaskDefinition::assigneeId)
                        .containsExactly("user-002", "user-003", "user-003", "user-002"));
    }

    @Test
    @DisplayName("applyTemplate breaks a tie on the lowest staff id when nobody leads the department")
    void applyTemplateBreaksTiesOnLowestStaffId() {
        // Both GROCERY, both plain associates on the programme, and listed so that membership order
        // disagrees with id order. Without a total ordering the winner would depend on that order.
        seedProject("project-003", ProjectStatus.ACTIVE, "store-001",
                member("user-004", ProjectRole.ASSOCIATE),
                member("user-003", ProjectRole.ASSOCIATE));

        final ApplyTemplateResponse first = projectService.applyTemplate("project-003", standardTemplate(null));
        final ApplyTemplateResponse second = projectService.applyTemplate("project-003", standardTemplate(null));

        assertThat(first.assignments()).extracting(TemplateAssignmentResponse::assigneeId)
                .containsExactly(null, "user-003", "user-003", null);
        assertThat(second.assignments()).isEqualTo(first.assignments());
    }

    @Test
    @DisplayName("applyTemplate treats a member who has left as ineligible")
    void applyTemplateSkipsInactiveMembers() {
        userRepository.remove("user-003");
        userRepository.with("user-003", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY", false);
        seedProject("project-004", ProjectStatus.ACTIVE, "store-001",
                member("user-003", ProjectRole.DEPARTMENT_LEAD));

        final ApplyTemplateResponse response = projectService.applyTemplate("project-004", standardTemplate(null));

        assertThat(response.taskCount()).isEqualTo(4);
        // Named by position rather than containsOnlyNulls(): user-003 is the GROCERY member, so only
        // lines 2 and 3 carry the signal. Drop the active filter and these two become user-003.
        assertThat(response.assignments())
                .extracting(TemplateAssignmentResponse::title, TemplateAssignmentResponse::assigneeId)
                .containsExactly(
                        tuple(ENTRANCE_BAY, null),
                        tuple(GROCERY_AISLES, null),
                        tuple(SHELF_LABELS, null),
                        tuple(PHOTOGRAPH_BAYS, null));
    }

    @Test
    @DisplayName("applyTemplate ignores a department lead at the store who is not on the programme")
    void applyTemplateConsidersProgrammeMembersOnly() {
        // user-003 leads GROCERY at store-001 but is not a member here, so the line stays unassigned.
        seedProject("project-005", ProjectStatus.ACTIVE, "store-001",
                member("user-002", ProjectRole.STORE_MANAGER));

        final ApplyTemplateResponse response = projectService.applyTemplate("project-005", standardTemplate(null));

        assertThat(response.assignments()).extracting(TemplateAssignmentResponse::assigneeId)
                .containsExactly("user-002", null, null, "user-002");
    }

    @Test
    @DisplayName("applyTemplate raises PROJECT_NOT_FOUND for an unknown programme and publishes nothing")
    void applyTemplateRejectsUnknownProgramme() {
        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> projectService.applyTemplate("project-999", standardTemplate(null)))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("PROJECT_NOT_FOUND");
                    assertThat(error.getStatusCode()).isEqualTo(404);
                });
        assertThat(templateEvents()).isEmpty();
    }

    @Test
    @DisplayName("applyTemplate refuses a closed programme with PROGRAMME_CLOSED and publishes nothing")
    void applyTemplateRefusesClosedProgramme() {
        seedProject("project-006", ProjectStatus.CLOSED, "store-001",
                member("user-002", ProjectRole.STORE_MANAGER));

        assertThatExceptionOfType(ConflictError.class)
                .isThrownBy(() -> projectService.applyTemplate("project-006", standardTemplate(null)))
                .satisfies(error -> {
                    // Not PROGRAMME_ALREADY_CLOSED: that code means "you tried to close a closed
                    // programme", a different rule with a different fix.
                    assertThat(error.getCode()).isEqualTo("PROGRAMME_CLOSED");
                    assertThat(error.getStatusCode()).isEqualTo(409);
                    assertThat(error.getMessage()).contains("project-006");
                });
        assertThat(templateEvents()).isEmpty();
    }

    @Test
    @DisplayName("applyTemplate rejects an unknown template id and lists the ones it knows")
    void applyTemplateRejectsUnknownTemplate() {
        seedProject("project-001", ProjectStatus.ACTIVE);

        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> projectService.applyTemplate(
                        "project-001", new ApplyTemplateRequest("PLANOGRAM_DELUXE", null)))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.getStatusCode()).isEqualTo(400);
                    assertThat(error.getDetails()).singleElement().satisfies(detail -> {
                        assertThat(detail).contains("templateId");
                        assertThat(detail).contains("PLANOGRAM_DELUXE");
                        assertThat(detail).contains("PLANOGRAM_STANDARD");
                    });
                });
        assertThat(templateEvents()).isEmpty();
    }

    @Test
    @DisplayName("applyTemplate rejects a requester the staff module does not know")
    void applyTemplateRejectsUnknownRequester() {
        seedProject("project-001", ProjectStatus.ACTIVE);

        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> projectService.applyTemplate("project-001", standardTemplate("user-999")))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.getDetails()).singleElement().satisfies(detail -> {
                        assertThat(detail).contains("requestedBy");
                        assertThat(detail).contains("user-999");
                    });
                });
        assertThat(templateEvents()).isEmpty();
    }

    @Test
    @DisplayName("applyTemplate records the requester as api when the request names nobody")
    void applyTemplateDefaultsRequestedByToApi() {
        seedProject("project-001", ProjectStatus.ACTIVE);

        projectService.applyTemplate("project-001", new ApplyTemplateRequest("PLANOGRAM_STANDARD", "   "));

        assertThat(templateEvents()).singleElement()
                .satisfies(event -> assertThat(event.requestedBy()).isEqualTo("api"));
    }

    @Test
    @DisplayName("applyTemplate trims a padded template id rather than rejecting it")
    void applyTemplateTrimsTemplateId() {
        seedProject("project-001", ProjectStatus.ACTIVE);

        final ApplyTemplateResponse response = projectService.applyTemplate(
                "project-001", new ApplyTemplateRequest("  PLANOGRAM_STANDARD  ", null));

        assertThat(response.templateId()).isEqualTo("PLANOGRAM_STANDARD");
        assertThat(templateEvents()).hasSize(1);
    }

    @Test
    @DisplayName("applyTemplate creates no activity itself, which is the whole point of the event")
    void applyTemplateWritesNothingToTheProgramme() {
        final Project before = seedProject("project-002", ProjectStatus.PLANNED, "store-002",
                member("user-005", ProjectRole.STORE_MANAGER));

        projectService.applyTemplate("project-002", standardTemplate("user-005"));

        // The programme is untouched: this endpoint publishes, and the activities module writes.
        assertThat(projectService.getById("project-002")).isEqualTo(before);
        assertThat(projectRepository.count()).isEqualTo(1);
    }
}
