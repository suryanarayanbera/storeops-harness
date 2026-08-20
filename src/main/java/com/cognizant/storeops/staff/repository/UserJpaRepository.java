package com.cognizant.storeops.staff.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the {@code users} table. Package-private: only {@code JpaUserRepository}
 * may use it.
 */
interface UserJpaRepository extends JpaRepository<UserEntity, String> {

    /** Staff lists read best alphabetically rather than newest-first. */
    Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("displayName"), Sort.Order.asc("id"));

    List<UserEntity> findByStoreId(String storeId, Sort sort);

    Optional<UserEntity> findByEmail(String email);
}
