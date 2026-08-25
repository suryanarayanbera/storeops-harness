package com.cognizant.storeops.support;

import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.domain.UserProfile;
import com.cognizant.storeops.staff.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Test double for {@link UserRepository}. Starts empty; tests build the roster they need.
 *
 * <p>Note what this fake does <em>not</em> do: it does not filter out inactive staff.
 * {@code findByStoreId} returns leavers too, exactly as {@code JpaUserRepository} does, because the
 * reports module counts them. Any {@code active} filtering is the service layer's job, and a fake
 * that quietly applied it here would hide a missing filter rather than expose it.
 */
public class FakeUserRepository extends FakeRepository<User, String> implements UserRepository {

    private static final Instant REGISTERED = Instant.parse("2026-01-06T08:00:00Z");

    public FakeUserRepository() {
        super(User::id);
    }

    @Override
    public List<User> findByStoreId(final String storeId) {
        return findMatching(user -> Objects.equals(user.storeId(), storeId));
    }

    @Override
    public Optional<User> findByEmail(final String email) {
        return email == null ? Optional.empty()
                : findMatching(user -> Objects.equals(user.email(), email)).stream().findFirst();
    }

    /** Adds a staff member. Region is always {@code region-north}, matching the seed. */
    public FakeUserRepository with(
            final String id,
            final StaffRole role,
            final String storeId,
            final String department,
            final boolean active) {
        save(new User(id, id + "@storeops.example", "Staff " + id, role, storeId, "region-north",
                active, new UserProfile(null, department, "EARLY"), REGISTERED));
        return this;
    }

    /** Adds an active staff member. */
    public FakeUserRepository with(
            final String id, final StaffRole role, final String storeId, final String department) {
        return with(id, role, storeId, department, true);
    }

    /**
     * The five seed staff members from {@code data.sql}, with their real roles, stores and
     * departments.
     */
    public FakeUserRepository withSeedRoster() {
        return with("user-001", StaffRole.REGIONAL_MANAGER, "store-001", "OPERATIONS")
                .with("user-002", StaffRole.STORE_MANAGER, "store-001", "OPERATIONS")
                .with("user-003", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY")
                .with("user-004", StaffRole.ASSOCIATE, "store-001", "GROCERY")
                .with("user-005", StaffRole.STORE_MANAGER, "store-002", "OPERATIONS");
    }

    public void remove(final String id) {
        deleteById(id);
    }
}
