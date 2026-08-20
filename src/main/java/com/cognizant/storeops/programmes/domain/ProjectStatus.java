package com.cognizant.storeops.programmes.domain;

/** Lifecycle state of a store programme. CLOSED is terminal and raises a domain event. */
public enum ProjectStatus {
    PLANNED,
    ACTIVE,
    CLOSED
}
