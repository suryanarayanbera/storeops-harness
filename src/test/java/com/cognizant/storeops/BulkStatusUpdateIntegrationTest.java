package com.cognizant.storeops;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Shift handover through the real stack: HTTP, the real service, the real transaction manager and
 * the seeded H2 database.
 *
 * <p>What only this test can show is the transaction boundary. A batch that fails halfway must
 * leave the writes before it committed and still perform the writes after it, which needs one
 * transaction per activity - not one for the batch. The service-level test cannot see that,
 * because a plain JUnit test has no transaction manager at all.
 *
 * <p>{@link DirtiesContext} rebuilds the context, and with it the {@code create-drop} schema and
 * {@code data.sql}, before every method. The seeded activities are mutated by these tests and by
 * others sharing the same in-memory database, so relying on their starting statuses without this
 * would make the suite order-dependent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class BulkStatusUpdateIntegrationTest {

    /** task-004's seeded modification time, used to prove a refused handover wrote nothing. */
    private static final String TASK_004_SEEDED_UPDATED_AT = "2026-01-06T08:03:00Z";

    @Autowired
    private MockMvc mockMvc;

    private ResultActions bulkStatus(final String body) throws Exception {
        return mockMvc.perform(patch("/api/tasks/bulk-status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertStoredStatus(final String taskId, final String expected) throws Exception {
        mockMvc.perform(get("/api/tasks/{id}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected));
    }

    @Test
    @DisplayName("a clean batch updates every listed activity and commits both")
    void cleanBatchUpdatesEveryActivity() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-001","status":"DONE"},
                            {"taskId":"task-002","status":"BLOCKED"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed", hasSize(0)))
                .andExpect(jsonPath("$.succeeded", hasSize(2)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-001"))
                .andExpect(jsonPath("$.succeeded[0].status").value("DONE"))
                .andExpect(jsonPath("$.succeeded[1].id").value("task-002"))
                .andExpect(jsonPath("$.succeeded[1].status").value("BLOCKED"));

        assertStoredStatus("task-001", "DONE");
        assertStoredStatus("task-002", "BLOCKED");
    }

    @Test
    @DisplayName("an unknown id fails alone and its neighbour's write is still committed")
    void unknownIdFailsAloneAndNeighbourCommits() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-001","status":"DONE"},
                            {"taskId":"task-999","status":"DONE"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(1)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-001"))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-999"))
                .andExpect(jsonPath("$.failed[0].code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.failed[0].statusCode").value(404));

        assertStoredStatus("task-001", "DONE");
    }

    @Test
    @DisplayName("a DONE activity is refused, and listing it first does not stop the rest of the batch")
    void terminalActivityFailsAloneEvenWhenListedFirst() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-003","status":"BLOCKED"},
                            {"taskId":"task-002","status":"DONE"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-003"))
                .andExpect(jsonPath("$.failed[0].code").value("TASK_TRANSITION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.failed[0].statusCode").value(409))
                .andExpect(jsonPath("$.succeeded", hasSize(1)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-002"));

        assertStoredStatus("task-003", "DONE");
        assertStoredStatus("task-002", "DONE");
    }

    @Test
    @DisplayName("a target status other than DONE or BLOCKED fails that activity only")
    void unsupportedTargetStatusFailsThatActivityOnly() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-001","status":"IN_PROGRESS"},
                            {"taskId":"task-002","status":"DONE"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-001"))
                .andExpect(jsonPath("$.failed[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0].statusCode").value(400))
                .andExpect(jsonPath("$.succeeded", hasSize(1)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-002"));

        assertStoredStatus("task-001", "TODO");
    }

    @Test
    @DisplayName("an activity already BLOCKED is reported as unchanged and is not rewritten")
    void noOpTransitionIsReportedAsUnchanged() throws Exception {
        bulkStatus("{\"updates\":[{\"taskId\":\"task-004\",\"status\":\"BLOCKED\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(0)))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-004"))
                .andExpect(jsonPath("$.failed[0].code").value("TASK_STATUS_UNCHANGED"))
                .andExpect(jsonPath("$.failed[0].statusCode").value(409));

        mockMvc.perform(get("/api/tasks/task-004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.updatedAt").value(TASK_004_SEEDED_UPDATED_AT));
    }

    @Test
    @DisplayName("a batch in which everything fails is still 200 with a result body, not an error body")
    void batchWhereEverythingFailsIsStillOk() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-003","status":"DONE"},
                            {"taskId":"task-999","status":"BLOCKED"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(0)))
                .andExpect(jsonPath("$.failed", hasSize(2)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-003"))
                .andExpect(jsonPath("$.failed[1].taskId").value("task-999"))
                // A result body, not an ErrorResponse: no top-level code/statusCode/path.
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.statusCode").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    @DisplayName("naming the same activity twice rejects the whole batch and writes nothing")
    void duplicateTaskIdsAreRejectedWholesale() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-001","status":"DONE"},
                            {"taskId":"task-001","status":"BLOCKED"}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.path").value("/api/tasks/bulk-status"));

        assertStoredStatus("task-001", "TODO");
    }

    @Test
    @DisplayName("an empty batch is rejected as a whole request")
    void emptyBatchIsRejected() throws Exception {
        bulkStatus("{\"updates\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a failure in the middle of a batch neither rolls back the write before it nor blocks the one after")
    void eachActivityCommitsOnItsOwn() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-001","status":"BLOCKED"},
                            {"taskId":"task-999","status":"BLOCKED"},
                            {"taskId":"task-002","status":"BLOCKED"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(2)))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-999"));

        assertStoredStatus("task-001", "BLOCKED");
        assertStoredStatus("task-002", "BLOCKED");
    }

    @Test
    @DisplayName("the single-activity path still works and the bulk path is never read as an activity id")
    void singleActivityPathIsUnaffected() throws Exception {
        mockMvc.perform(patch("/api/tasks/task-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-001"))
                .andExpect(jsonPath("$.status").value("DONE"))
                // A single TaskResponse, not a bulk result.
                .andExpect(jsonPath("$.succeeded").doesNotExist());

        // The literal path wins over /{id}: no TASK_NOT_FOUND for an activity called "bulk-status".
        bulkStatus("{\"updates\":[{\"taskId\":\"task-002\",\"status\":\"DONE\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(1)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-002"));
    }
}
