package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.support.FailingStatusSubscriber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A subscriber that throws must not cost the outgoing shift its handover.
 *
 * <p>Held in its own class rather than added to {@code BulkStatusEventDeliveryIntegrationTest},
 * because {@link FailingStatusSubscriber} throws on every {@code TaskStatusChangedEvent} the
 * context publishes. Importing it there would have it fire through all five delivery scenarios,
 * where the containment it is testing would instead be noise.
 *
 * <p>The exception is absorbed by the {@code ErrorHandler} bean in {@code EventBusConfiguration}.
 * Without that bean Spring's {@code SimpleApplicationEventMulticaster} propagates a subscriber
 * failure back to the publisher, and an alerts-module bug would fail an activities-module
 * request - the coupling the event bus exists to prevent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FailingStatusSubscriber.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class BulkStatusSubscriberIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FailingStatusSubscriber failingStatusSubscriber;

    @Test
    @DisplayName("a throwing subscriber does not break the batch, and both activities still commit")
    void throwingSubscriberDoesNotBreakTheBatch() throws Exception {
        final int before = failingStatusSubscriber.invocationCount();

        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"updates":[{"taskId":"task-001","status":"BLOCKED"},
                                            {"taskId":"task-002","status":"BLOCKED"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(2)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-001"))
                .andExpect(jsonPath("$.succeeded[1].id").value("task-002"))
                .andExpect(jsonPath("$.failed", hasSize(0)));

        mockMvc.perform(get("/api/tasks/task-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
        mockMvc.perform(get("/api/tasks/task-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        // Asserted so the test cannot pass merely because dispatch was broken and the failing
        // subscriber was never reached: two transitions, two invocations.
        assertThat(failingStatusSubscriber.invocationCount()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("the real listener still raises its alert even though another subscriber threw")
    void theRealListenerIsUnaffectedByTheFailingOne() throws Exception {
        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":[{\"taskId\":\"task-001\",\"status\":\"BLOCKED\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(1)));

        mockMvc.perform(get("/api/notifications").param("recipientId", "user-004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.alertType == 'ESCALATION' && @.sourceRef == 'task-001')]",
                        hasSize(1)));

        assertThat(failingStatusSubscriber.invocationCount()).isEqualTo(1);
    }
}
