package com.cognizant.storeops.staff.repository;

import com.cognizant.storeops.staff.domain.User;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * H2-backed {@link UserRepository}.
 *
 * <p>Read-only, matching the module rule: staff data is readable by other modules through
 * {@code UserService} and writable by nobody. No save or delete is exposed here either, so the
 * rule holds all the way down to the storage layer.
 */
@Repository
public class JpaUserRepository implements UserRepository {

    private final UserJpaRepository users;

    JpaUserRepository(final UserJpaRepository users) {
        this.users = users;
    }

    @Override
    public Optional<User> findById(final String id) {
        return id == null ? Optional.empty() : users.findById(id).map(UserEntity::toDomain);
    }

    @Override
    public boolean existsById(final String id) {
        return id != null && users.existsById(id);
    }

    @Override
    public List<User> findAll() {
        return toDomain(users.findAll(UserJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<User> findByStoreId(final String storeId) {
        return toDomain(users.findByStoreId(storeId, UserJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<User> findByRegionId(final String regionId) {
        return toDomain(users.findByRegionId(regionId, UserJpaRepository.DEFAULT_SORT));
    }

    @Override
    public Optional<User> findByEmail(final String email) {
        return email == null ? Optional.empty() : users.findByEmail(email).map(UserEntity::toDomain);
    }

    private static List<User> toDomain(final List<UserEntity> entities) {
        return entities.stream().map(UserEntity::toDomain).toList();
    }
}
