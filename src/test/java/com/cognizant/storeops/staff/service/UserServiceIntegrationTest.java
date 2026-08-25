package com.cognizant.storeops.staff.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The staff read the alerts module composes its escalation chain from, against the real H2 schema.
 *
 * <p>Worth an integration test rather than a fake: the query filters on an {@code @Enumerated} column,
 * so what is being proved is that the enum round-trips through the database as a string and that the
 * store filter is applied there rather than in Java.
 *
 * <p>Nothing in the suite can add or remove a staff member - {@code UserService} exposes no mutator -
 * so these counts are exact and stay exact whatever order the suite runs in.
 */
@SpringBootTest
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Test
    @DisplayName("findByStoreIdAndRole returns only the staff holding that role at that store")
    void findByStoreIdAndRoleFiltersOnBothCriteria() {
        assertThat(userService.findByStoreIdAndRole("store-001", StaffRole.STORE_MANAGER))
                .extracting(User::id).containsExactly("user-002");

        // user-005 is a STORE_MANAGER too, but at store-002.
        assertThat(userService.findByStoreIdAndRole("store-002", StaffRole.STORE_MANAGER))
                .extracting(User::id).containsExactly("user-005");

        assertThat(userService.findByStoreIdAndRole("store-001", StaffRole.DEPARTMENT_LEAD))
                .extracting(User::id).containsExactly("user-003");
    }

    @Test
    @DisplayName("findByStoreIdAndRole returns the department alongside the role, and no match is empty")
    void findByStoreIdAndRoleCarriesTheProfileAndToleratesNoMatch() {
        // The department is what the alerts module matches an assignee against, so it has to survive
        // the round trip out of the flattened profile columns.
        assertThat(userService.findByStoreIdAndRole("store-001", StaffRole.DEPARTMENT_LEAD))
                .singleElement()
                .satisfies(lead -> {
                    assertThat(lead.profile().department()).isEqualTo("GROCERY");
                    assertThat(lead.active()).isTrue();
                });

        assertThat(userService.findByStoreIdAndRole("store-404", StaffRole.STORE_MANAGER)).isEmpty();
        assertThat(userService.findByStoreIdAndRole("store-002", StaffRole.DEPARTMENT_LEAD)).isEmpty();
        assertThat(userService.findByStoreIdAndRole(null, StaffRole.STORE_MANAGER)).isEmpty();
        assertThat(userService.findByStoreIdAndRole("store-001", null)).isEmpty();
    }
}
