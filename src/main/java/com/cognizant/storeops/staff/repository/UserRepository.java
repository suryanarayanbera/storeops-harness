package com.cognizant.storeops.staff.repository;

import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import java.util.List;
import java.util.Optional;

/**
 * Data access for staff members. Owned by the staff module.
 *
 * <p>No other module may import this interface - cross-module reads go through
 * {@code UserService}. Enforced by {@code ModuleBoundaryTest}.
 */
public interface UserRepository {

    Optional<User> findById(String id);

    boolean existsById(String id);

    List<User> findAll();

    List<User> findByStoreId(String storeId);

    /** Everyone holding one position at one store, active or not. */
    List<User> findByStoreIdAndRole(String storeId, StaffRole role);

    Optional<User> findByEmail(String email);
}
