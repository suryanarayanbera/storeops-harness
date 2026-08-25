package com.cognizant.storeops;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StoreOps - retail store operations management REST API.
 *
 * <p>Capstone reference codebase. Storage is in-memory and every service is a stub: the point of
 * this application is that its module boundaries, layering and error contract are correct and
 * mechanically checkable, not that its business logic is complete.
 *
 * <p>{@code @ConfigurationPropertiesScan} rather than
 * {@code @EnableConfigurationProperties(SlaSweepProperties.class)}: the scan discovers module-owned
 * properties by package, so the application root never names a type belonging to a module.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class StoreOpsApplication {

    public static void main(final String[] args) {
        SpringApplication.run(StoreOpsApplication.class, args);
    }

    /**
     * Injected wherever a service needs the current time, so tests can pin it with
     * {@code Clock.fixed(...)} instead of tolerating wall-clock drift.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
