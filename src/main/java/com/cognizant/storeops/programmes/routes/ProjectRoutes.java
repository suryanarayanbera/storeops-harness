package com.cognizant.storeops.programmes.routes;

import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.dto.ApplyTemplateRequest;
import com.cognizant.storeops.programmes.dto.ApplyTemplateResponse;
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.programmes.dto.ProjectResponse;
import com.cognizant.storeops.programmes.service.ProjectService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP surface of the programmes module. */
@RestController
@RequestMapping("/api/projects")
public class ProjectRoutes {

    private final ProjectService projectService;

    public ProjectRoutes(final ProjectService projectService) {
        this.projectService = projectService;
    }

    /** Endpoint 5: {@code GET /api/projects}. */
    @GetMapping
    public ResponseEntity<List<ProjectResponse>> listProjects(
            @RequestParam(required = false) final ProjectStatus status,
            @RequestParam(required = false) final String storeId) {
        final List<ProjectResponse> body = projectService.list(status, storeId).stream()
                .map(ProjectResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** Endpoint 6: {@code POST /api/projects}. */
    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody final CreateProjectRequest request) {
        final ProjectResponse created = ProjectResponse.from(projectService.create(request));
        return ResponseEntity.created(URI.create("/api/projects/" + created.id())).body(created);
    }

    /**
     * Endpoint 12: {@code POST /api/projects/{id}/templates}.
     *
     * <p>Nested under the existing {@code /api/projects} base rather than given a
     * {@code /api/programmes} one of its own. The module is {@code programmes} and the route base is
     * {@code /api/projects}; that mismatch is deliberate across the whole API and this endpoint keeps
     * to it.
     *
     * <p>{@code 202 Accepted}, because the activities are created by the {@code activities} module
     * after this request commits. No {@code Location} header: there is no single resource to point
     * at, and the created activities have no ids yet. Callers read them back with
     * {@code GET /api/tasks?storeId={storeId}}.
     */
    @PostMapping("/{id}/templates")
    public ResponseEntity<ApplyTemplateResponse> applyTemplate(
            @PathVariable final String id,
            @Valid @RequestBody final ApplyTemplateRequest request) {
        return ResponseEntity.accepted().body(projectService.applyTemplate(id, request));
    }
}
