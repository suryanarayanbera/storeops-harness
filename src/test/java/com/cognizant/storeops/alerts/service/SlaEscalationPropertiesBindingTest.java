package com.cognizant.storeops.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The grace period as the application actually ships it.
 *
 * <p>{@code SlaEscalationPropertiesTest} proves the record's defaulting rules in isolation; this proves
 * the property is declared in {@code application.yml} and reaches the bean, which is the part a unit
 * test cannot see. The override case is covered by {@code SlaEscalationIntegrationTest}.
 */
@SpringBootTest
class SlaEscalationPropertiesBindingTest {

    @Autowired
    private SlaEscalationProperties escalationProperties;

    @Test
    @DisplayName("the shipped configuration binds a two hour grace period")
    void shippedConfigurationBindsTwoHours() {
        assertThat(escalationProperties.gracePeriod()).isEqualTo(Duration.ofHours(2));
    }
}
