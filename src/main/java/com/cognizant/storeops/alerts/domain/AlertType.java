package com.cognizant.storeops.alerts.domain;

/** Operational trigger behind an alert. */
public enum AlertType {
    INVENTORY,
    SLA_BREACH,
    SHIFT_HANDOVER,
    ESCALATION
}
