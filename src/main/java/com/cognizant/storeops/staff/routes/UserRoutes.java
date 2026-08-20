package com.cognizant.storeops.staff.routes;

import com.cognizant.storeops.staff.dto.UserResponse;
import com.cognizant.storeops.staff.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface of the staff module.
 *
 * <p>Routes layer: request mapping, validation and response shaping only. No business logic, no
 * repository access.
 */
@RestController
@RequestMapping("/api/users")
public class UserRoutes {

    private final UserService userService;

    public UserRoutes(final UserService userService) {
        this.userService = userService;
    }

    /** Endpoint 7: {@code GET /api/users/{id}}. */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable final String id) {
        return ResponseEntity.ok(UserResponse.from(userService.getById(id)));
    }
}
