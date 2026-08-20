package com.cognizant.storeops.programmes.routes;

import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.programmes.dto.ProjectResponse;
import com.cognizant.storeops.programmes.service.ProjectService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
}
