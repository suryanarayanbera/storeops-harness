package com.cognizant.storeops.programmes.routes;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectMember;
import com.cognizant.storeops.programmes.domain.ProjectRole;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.dto.ApplyTemplateRequest;
import com.cognizant.storeops.programmes.dto.ApplyTemplateResponse;
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.programmes.dto.TemplateAssignmentResponse;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Routes-layer slice test for the programmes module. */
@WebMvcTest(ProjectRoutes.class)
class ProjectRoutesTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    private static Project sampleProject() {
        return new Project("project-001", "Spring seasonal rollout", "Aisle resets",
                ProjectStatus.ACTIVE, "store-001", "region-north", "user-002",
                List.of(new ProjectMember("user-002", ProjectRole.STORE_MANAGER, NOW)), NOW, null);
    }

    @Test
    @DisplayName("GET /api/projects returns 200 with the programme list and its membership")
    void listReturnsProjects() throws Exception {
        when(projectService.list(null, null)).thenReturn(List.of(sampleProject()));

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("project-001"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].members", hasSize(1)))
                .andExpect(jsonPath("$[0].members[0].role").value("STORE_MANAGER"));
    }

    @Test
    @DisplayName("GET /api/projects passes status and storeId filters through to the service")
    void listAppliesFilters() throws Exception {
        when(projectService.list(ProjectStatus.CLOSED, "store-002")).thenReturn(List.of());

        mockMvc.perform(get("/api/projects").param("status", "CLOSED").param("storeId", "store-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("POST /api/projects returns 201 with a Location header")
    void createReturnsCreated() throws Exception {
        when(projectService.create(any(CreateProjectRequest.class))).thenReturn(sampleProject());

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Spring seasonal rollout","storeId":"store-001",
                                 "regionId":"region-north","ownerId":"user-002"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/projects/project-001"))
                .andExpect(jsonPath("$.id").value("project-001"));
    }

    @Test
    @DisplayName("POST /api/projects returns 400 listing every missing required field")
    void createRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details", hasSize(3)))
                .andExpect(jsonPath("$.details[0]").value("name: must not be blank"))
                .andExpect(jsonPath("$.details[1]").value("ownerId: must not be blank"))
                .andExpect(jsonPath("$.details[2]").value("storeId: must not be blank"));
    }

    @Test
    @DisplayName("POST /api/projects surfaces an unknown owner as a 400 with details")
    void createSurfacesUnknownOwner() throws Exception {
        when(projectService.create(any(CreateProjectRequest.class)))
                .thenThrow(new ValidationError("Owner is not a known staff member",
                        List.of("ownerId: unknown staff member 'user-999'")));

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Refit\",\"storeId\":\"store-001\",\"ownerId\":\"user-999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("ownerId: unknown staff member 'user-999'"));
    }

    // ---------------------------------------------- POST /api/projects/{id}/templates

    private static ApplyTemplateResponse sampleTemplateResponse() {
        return new ApplyTemplateResponse("project-002", "PLANOGRAM_STANDARD", 2, List.of(
                new TemplateAssignmentResponse("Reset entrance promotional bay", "OPERATIONS", "HIGH", "user-005"),
                new TemplateAssignmentResponse("Reset grocery aisle planograms", "GROCERY", "HIGH", null)));
    }

    @Test
    @DisplayName("POST /api/projects/{id}/templates returns 202 with the assignment echo")
    void applyTemplateReturnsAccepted() throws Exception {
        when(projectService.applyTemplate(eq("project-002"), any(ApplyTemplateRequest.class)))
                .thenReturn(sampleTemplateResponse());

        mockMvc.perform(post("/api/projects/project-002/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\",\"requestedBy\":\"user-005\"}"))
                // 202, not 201: the activities are created by the activities module after this
                // request commits, so there is no created resource to point a Location header at.
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.projectId").value("project-002"))
                .andExpect(jsonPath("$.templateId").value("PLANOGRAM_STANDARD"))
                .andExpect(jsonPath("$.taskCount").value(2))
                .andExpect(jsonPath("$.assignments", hasSize(2)))
                .andExpect(jsonPath("$.assignments[0].title").value("Reset entrance promotional bay"))
                .andExpect(jsonPath("$.assignments[0].department").value("OPERATIONS"))
                .andExpect(jsonPath("$.assignments[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.assignments[0].assigneeId").value("user-005"))
                .andExpect(jsonPath("$.assignments[1].assigneeId").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/projects/{id}/templates rejects a blank templateId before reaching the service")
    void applyTemplateRejectsBlankTemplateId() throws Exception {
        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0]").value("templateId: must not be blank"));

        // Bean validation on the DTO, so the service is never consulted.
        verify(projectService, never()).applyTemplate(any(), any());
    }

    @Test
    @DisplayName("POST /api/projects/{id}/templates rejects a missing body field the same way")
    void applyTemplateRejectsMissingTemplateId() throws Exception {
        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("templateId: must not be blank"));

        verify(projectService, never()).applyTemplate(any(), any());
    }

    @Test
    @DisplayName("POST /api/projects/{id}/templates surfaces an unknown programme as 404 PROJECT_NOT_FOUND")
    void applyTemplateSurfacesUnknownProgramme() throws Exception {
        when(projectService.applyTemplate(eq("project-999"), any(ApplyTemplateRequest.class)))
                .thenThrow(NotFoundError.of("Project", "project-999"));

        mockMvc.perform(post("/api/projects/project-999/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/projects/{id}/templates surfaces a closed programme as 409 PROGRAMME_CLOSED")
    void applyTemplateSurfacesClosedProgramme() throws Exception {
        when(projectService.applyTemplate(eq("project-007"), any(ApplyTemplateRequest.class)))
                .thenThrow(new ConflictError("PROGRAMME_CLOSED",
                        "Programme 'project-007' is closed; activities cannot be added to it"));

        mockMvc.perform(post("/api/projects/project-007/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROGRAMME_CLOSED"))
                .andExpect(jsonPath("$.statusCode").value(409));
    }

    @Test
    @DisplayName("POST /api/projects/{id}/templates surfaces an unknown template as 400 with details")
    void applyTemplateSurfacesUnknownTemplate() throws Exception {
        when(projectService.applyTemplate(eq("project-001"), any(ApplyTemplateRequest.class)))
                .thenThrow(new ValidationError("Template is not a known task template",
                        List.of("templateId: unknown template 'PLANOGRAM_DELUXE'")));

        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_DELUXE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("templateId: unknown template 'PLANOGRAM_DELUXE'"));
    }
}
