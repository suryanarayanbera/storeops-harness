package com.cognizant.storeops.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.util.ErrorHandler;

/**
 * Central failure policy for event delivery.
 *
 * <p>Spring's default multicaster propagates a listener exception back to the publisher. That would
 * make the event bus worse than a direct call: the alerts module could fail a
 * {@code PATCH /api/tasks/{id}} even though the activity was updated successfully. Replacing the
 * multicaster with one that carries an {@link ErrorHandler} restores the guarantee the bus exists to
 * provide - a consuming module cannot break a publishing one.
 *
 * <p>Overriding {@code applicationEventMulticaster} is Spring's documented extension point for this.
 * The bean name must match exactly or the default is used and the handler is silently ignored, which
 * is why {@code EventBusConfigurationTest} asserts the wiring rather than trusting it.
 */
@Configuration
public class EventBusConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EventBusConfiguration.class);

    /**
     * Multicaster with subscriber isolation. Dispatch stays synchronous: injecting a
     * {@link TaskExecutor} here is all that would be needed to make it asynchronous, but a stub
     * deployment is easier to reason about when a published event has been handled by the time
     * {@code publish} returns.
     */
    @Bean(name = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)
    public ApplicationEventMulticaster applicationEventMulticaster(
            @Qualifier("eventSubscriberErrorHandler") final ErrorHandler errorHandler) {
        final SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setErrorHandler(errorHandler);
        return multicaster;
    }

    /** Logs and swallows, so one failing consumer neither aborts the publisher nor starves its peers. */
    @Bean
    public ErrorHandler eventSubscriberErrorHandler() {
        return throwable -> LOG.error("Event subscriber failed; publisher and other subscribers unaffected",
                throwable);
    }
}
