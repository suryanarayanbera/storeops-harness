package com.cognizant.storeops.alerts.repository;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the {@code sla_breaches} table.
 *
 * <p>Package-private on purpose. {@code JpaSlaBreachRepository} is the only thing that may use it; the
 * rest of the module depends on {@link SlaBreachRepository}, which says nothing about JPA.
 *
 * <p>No declarations of its own: every episode is reached by its activity id, so the inherited
 * {@code findById}, {@code save}, {@code existsById} and {@code deleteById} cover the whole port. There
 * is deliberately no sort constant - nothing lists breaches, and a listing has no caller until the
 * breach-tracker endpoint arrives.
 */
interface SlaBreachJpaRepository extends JpaRepository<SlaBreachEntity, String> {
}
