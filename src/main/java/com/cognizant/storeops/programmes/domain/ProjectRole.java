package com.cognizant.storeops.programmes.domain;

/**
 * Role a staff member holds within one store programme.
 *
 * <p>Distinct from {@code StaffRole}: a DEPARTMENT_LEAD in the staff hierarchy can be an ASSOCIATE
 * on a particular programme. Programme membership is owned by the programmes module.
 */
public enum ProjectRole {
    STORE_MANAGER,
    DEPARTMENT_LEAD,
    ASSOCIATE
}
