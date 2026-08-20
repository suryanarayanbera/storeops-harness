package com.cognizant.storeops.staff.routes;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.domain.UserProfile;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Routes-layer slice test for the staff module. */
@WebMvcTest(UserRoutes.class)
class UserRoutesTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    @DisplayName("GET /api/users/{id} returns 200 with the staff member")
    void getReturnsUser() throws Exception {
        when(userService.getById("user-003")).thenReturn(new User(
                "user-003", "lena.brandt@storeops.example", "Lena Brandt", StaffRole.DEPARTMENT_LEAD,
                "store-001", "region-north", true, new UserProfile("+44", "GROCERY", "LATE"), NOW));

        mockMvc.perform(get("/api/users/user-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-003"))
                .andExpect(jsonPath("$.displayName").value("Lena Brandt"))
                .andExpect(jsonPath("$.role").value("DEPARTMENT_LEAD"))
                .andExpect(jsonPath("$.department").value("GROCERY"));
    }

    @Test
    @DisplayName("GET /api/users/{id} does not expose the staff member's email")
    void getOmitsEmail() throws Exception {
        when(userService.getById("user-003")).thenReturn(new User(
                "user-003", "lena.brandt@storeops.example", "Lena Brandt", StaffRole.DEPARTMENT_LEAD,
                "store-001", "region-north", true, UserProfile.empty(), NOW));

        mockMvc.perform(get("/api/users/user-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/users/{id} returns 404 with the typed error body when missing")
    void getMissingUserReturnsTypedNotFound() throws Exception {
        when(userService.getById("user-999")).thenThrow(NotFoundError.of("User", "user-999"));

        mockMvc.perform(get("/api/users/user-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.path").value("/api/users/user-999"));
    }
}
