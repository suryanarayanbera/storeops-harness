package com.cognizant.storeops.staff.service;

import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.repository.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Staff business logic, and the only entry point other modules may use to read staff data.
 *
 * <p>StoreOps architecture rule: <em>staff is read-only for other modules</em>. That rule is
 * structural here rather than conventional - this service exposes no mutator at all, so no other
 * module can write staff data even by mistake. Registration and profile maintenance are future work
 * and will arrive as staff-owned endpoints, not as a cross-module capability.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Loads a staff member.
     *
     * @throws NotFoundError when no staff member has that id
     */
    public User getById(final String id) {
        return userRepository.findById(id).orElseThrow(() -> NotFoundError.of("User", id));
    }

    /** Non-throwing lookup, for callers treating a missing staff member as an ordinary outcome. */
    public Optional<User> findById(final String id) {
        return userRepository.findById(id);
    }

    public boolean exists(final String id) {
        return userRepository.existsById(id);
    }

    /** Roster for one store. Used by the reports module for headcount aggregation. */
    public List<User> findByStoreId(final String storeId) {
        return userRepository.findByStoreId(storeId);
    }

    /**
     * Active staff at one store holding one role, lowest id first.
     *
     * <p>Exists for alert routing: the alerts module needs to name a Department Lead or a Store
     * Manager, and this is the read that lets it do so without touching {@code UserRepository}.
     *
     * <p>The {@code active} filter is applied here rather than in the repository because
     * {@link #findByStoreId} deliberately returns leavers too - the reports module counts them. A
     * caller looking for someone to notify wants neither a leaver nor a coin toss between two
     * equally valid candidates, so the ordering is total and by id.
     *
     * @param storeId store to look in; a null returns empty rather than every store
     * @param role    role to match exactly; a null returns empty
     * @return matching active staff, ascending by id, never null
     */
    public List<User> findByStoreIdAndRole(final String storeId, final StaffRole role) {
        if (storeId == null || role == null) {
            return List.of();
        }
        return userRepository.findByStoreId(storeId).stream()
                .filter(User::active)
                .filter(user -> user.role() == role)
                .sorted(Comparator.comparing(User::id))
                .toList();
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }
}
