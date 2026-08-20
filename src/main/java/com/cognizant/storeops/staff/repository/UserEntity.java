package com.cognizant.storeops.staff.repository;

import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.domain.UserProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persistence mapping for the {@code users} table.
 *
 * <p>{@code UserProfile} is flattened into columns on this table rather than given its own, since
 * a profile has no identity apart from the staff member it belongs to.
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "email", nullable = false, length = 200, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private StaffRole role;

    @Column(name = "store_id", length = 64)
    private String storeId;

    @Column(name = "region_id", length = 64)
    private String regionId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "department", length = 60)
    private String department;

    @Column(name = "shift_pattern", length = 30)
    private String shiftPattern;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. Not for application use. */
    protected UserEntity() {
        // Hibernate instantiates through reflection.
    }

    static UserEntity fromDomain(final User user) {
        final UserEntity entity = new UserEntity();
        entity.id = user.id();
        entity.email = user.email();
        entity.displayName = user.displayName();
        entity.role = user.role();
        entity.storeId = user.storeId();
        entity.regionId = user.regionId();
        entity.active = user.active();
        entity.phone = user.profile().phone();
        entity.department = user.profile().department();
        entity.shiftPattern = user.profile().shiftPattern();
        entity.createdAt = user.createdAt();
        return entity;
    }

    User toDomain() {
        return new User(id, email, displayName, role, storeId, regionId, active,
                new UserProfile(phone, department, shiftPattern), createdAt);
    }
}
