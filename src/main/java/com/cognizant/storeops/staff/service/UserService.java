package com.cognizant.storeops.staff.service;

import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.repository.UserRepository;
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
     * Everyone holding one position at one store, active or not.
     *
     * <p>Answers "who holds this role here", and stops there. Which of them is accountable for a
     * particular piece of work is the asking module's policy, not staff's - the alerts module composes
     * this read into its own escalation chain rather than asking staff to know about SLAs.
     */
    public List<User> findByStoreIdAndRole(final String storeId, final StaffRole role) {
        return userRepository.findByStoreIdAndRole(storeId, role);
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }
}
