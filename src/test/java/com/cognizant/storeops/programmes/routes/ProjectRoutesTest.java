package com.cognizant.storeops.programmes.routes;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
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
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.programmes.service.ProjectService;
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
}
