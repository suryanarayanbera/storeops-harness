package com.cognizant.storeops.staff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.support.FakeUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Service-layer test for the staff module.
 *
 * <p>Most of this class covers {@code findByStoreIdAndRole}, the lookup the alerts module routes SLA
 * breach alerts through. Its three responsibilities - exclude leavers, match the role exactly, order
 * totally - are each the difference between alerting the right person and alerting nobody or a
 * different person on every run, and none of them is visible from the alerts module's own tests.
 */
class UserServiceTest {

    private FakeUserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepository();
        userService = new UserService(userRepository);
    }

    @Test
    @DisplayName("getById raises a typed NotFoundError for an unknown id")
    void getByIdRaisesTypedNotFound() {
        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> userService.getById("user-999"))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("USER_NOT_FOUND");
                    assertThat(error.getStatusCode()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("findByStoreIdAndRole returns only staff holding that exact role in that store")
    void findByStoreIdAndRoleMatchesStoreAndRole() {
        userRepository.withSeedRoster();

        assertThat(userService.findByStoreIdAndRole("store-001", StaffRole.DEPARTMENT_LEAD))
                .extracting(User::id).containsExactly("user-003");
        assertThat(userService.findByStoreIdAndRole("store-001", StaffRole.STORE_MANAGER))
                .extracting(User::id).containsExactly("user-002");
        // user-005 is a STORE_MANAGER, but at store-002.
        assertThat(userService.findByStoreIdAndRole("store-002", StaffRole.STORE_MANAGER))
                .extracting(User::id).containsExactly("user-005");
        assertThat(userService.findByStoreIdAndRole("store-002", StaffRole.DEPARTMENT_LEAD)).isEmpty();
    }

    @Test
    @DisplayName("findByStoreIdAndRole excludes leavers, even though findByStoreId includes them")
    void findByStoreIdAndRoleExcludesInactiveStaff() {
        userRepository.with("user-010", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY", false);

        assertThat(userService.findByStoreIdAndRole("store-001", StaffRole.DEPARTMENT_LEAD)).isEmpty();
        // The unfiltered roster read still sees them, which is what reports depends on.
        assertThat(userService.findByStoreId("store-001")).extracting(User::id).containsExactly("user-010");
    }

    @Test
    @DisplayName("findByStoreIdAndRole orders by id, whatever order the roster arrived in")
    void findByStoreIdAndRoleOrdersById() {
        userRepository
                .with("user-007", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY")
                .with("user-003", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY")
                .with("user-005", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY");

        assertThat(userService.findByStoreIdAndRole("store-001", StaffRole.DEPARTMENT_LEAD))
                .extracting(User::id).containsExactly("user-003", "user-005", "user-007");
    }

    @Test
    @DisplayName("findByStoreIdAndRole returns empty for a null store or a null role, never everything")
    void findByStoreIdAndRoleRejectsNullCriteriaByReturningEmpty() {
        userRepository.withSeedRoster();

        assertThat(userService.findByStoreIdAndRole(null, StaffRole.DEPARTMENT_LEAD)).isEmpty();
        assertThat(userService.findByStoreIdAndRole("store-001", null)).isEmpty();
        assertThat(userService.findByStoreIdAndRole(null, null)).isEmpty();
    }

    @Test
    @DisplayName("findByStoreIdAndRole returns empty for a store with no staff at all")
    void findByStoreIdAndRoleReturnsEmptyForAnUnknownStore() {
        userRepository.withSeedRoster();

        assertThat(userService.findByStoreIdAndRole("store-003", StaffRole.STORE_MANAGER)).isEmpty();
    }

    @Test
    @DisplayName("findByRegionId returns every staff member in the region, across all its stores")
    void findByRegionIdSpansStores() {
        userRepository.withSeedRoster();

        // This read is what lets the reports module resolve region-north to store-001 and store-002;
        // there is no Store entity, so users.region_id is the only record of that membership.
        assertThat(userService.findByRegionId("region-north")).extracting(User::id)
                .containsExactly("user-001", "user-002", "user-003", "user-004", "user-005");
        assertThat(userService.findByRegionId("region-north")).extracting(User::storeId)
                .contains("store-001", "store-002");
    }

    @Test
    @DisplayName("findByRegionId returns empty for an unknown region rather than throwing")
    void findByRegionIdReturnsEmptyForAnUnknownRegion() {
        userRepository.withSeedRoster();

        assertThat(userService.findByRegionId("region-atlantis")).isEmpty();
    }

    @Test
    @DisplayName("findByRegionId returns empty for a null or blank region, never every region")
    void findByRegionIdRejectsBlankCriteriaByReturningEmpty() {
        userRepository.withSeedRoster();

        assertThat(userService.findByRegionId(null)).isEmpty();
        assertThat(userService.findByRegionId("")).isEmpty();
        assertThat(userService.findByRegionId("   ")).isEmpty();
    }

    @Test
    @DisplayName("findByRegionId keeps regions apart")
    void findByRegionIdSeparatesRegions() {
        userRepository
                .withRegion("user-020", StaffRole.STORE_MANAGER, "store-009", "region-south")
                .withRegion("user-021", StaffRole.ASSOCIATE, "store-001", "region-north");

        assertThat(userService.findByRegionId("region-south")).extracting(User::id)
                .containsExactly("user-020");
        assertThat(userService.findByRegionId("region-north")).extracting(User::id)
                .containsExactly("user-021");
    }

    @Test
    @DisplayName("findById is a non-throwing lookup and exists agrees with it")
    void findByIdIsNonThrowing() {
        userRepository.withSeedRoster();

        assertThat(userService.findById("user-003")).isPresent();
        assertThat(userService.findById("user-999")).isEmpty();
        assertThat(userService.exists("user-003")).isTrue();
        assertThat(userService.exists("user-999")).isFalse();
    }
}
