package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.support.FailingRollupSubscriber;
import com.cognizant.storeops.support.RecordingRollupSubscriber;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The regional rollup against the real wiring and the real seed.
 *
 * <p>{@code ReportServiceTest} proves the arithmetic over fakes, and {@code ReportRoutesTest} proves
 * the binding over a mocked service. Neither proves the figures a caller actually gets, because both
 * invent their own activities. This class asserts the numbers that fall out of {@code data.sql}
 * itself - including the ones the fakes cannot see, like {@code task-004} being {@code LOW} priority
 * and assigned to {@code user-005} - and it exercises the two cross-module reads for real:
 * {@code users.region_id} resolving {@code region-north} to two stores, and one
 * {@code TaskService.findByStoreId} per store.
 *
 * <p>The SLA sweep is disabled so a background cycle cannot publish events mid-test.
 * {@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} rebuilds the schema and re-runs {@code data.sql}
 * before each method: every assertion here counts rows in {@code tasks}, and {@code ApiSmokeTest}
 * creates an activity at {@code store-001}, so without this the totals would depend on which class
 * ran first.
 */
@SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")
@AutoConfigureMockMvc
@Import({RecordingRollupSubscriber.class, FailingRollupSubscriber.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class RegionalRollupIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReportService reportService;

    @Autowired
    private RecordingRollupSubscriber subscriber;

    @Autowired
    private FailingRollupSubscriber failingSubscriber;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("the rollup totals both seeded stores of region-north into one set of figures")
    void rollupAggregatesTheSeededRegion() throws Exception {
        mockMvc.perform(get("/api/reports/region/region-north"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionId").value("region-north"))
                .andExpect(jsonPath("$.generatedAt").exists())
                // store-001 holds task-001/002/003 and store-002 holds task-004.
                .andExpect(jsonPath("$.storeCount").value(2))
                .andExpect(jsonPath("$.totalActivities").value(4))
                .andExpect(jsonPath("$.completedActivities").value(1))
                .andExpect(jsonPath("$.completionRate").value(0.25))
                .andExpect(jsonPath("$.overdueCount").value(2))
                .andExpect(jsonPath("$.blockedCount").value(1));
    }

    @Test
    @DisplayName("the rollup breaks overdue counts down by category and keeps the zeroes")
    void rollupBreaksOverdueDownByCategory() throws Exception {
        mockMvc.perform(get("/api/reports/region/region-north"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueByCategory.length()").value(5))
                .andExpect(jsonPath("$.overdueByCategory.RESTOCKING").value(1))
                .andExpect(jsonPath("$.overdueByCategory.PLANOGRAM").value(1))
                // task-003 is COMPLIANCE and past due, but DONE, so it is not overdue. task-004 is
                // AUDIT and not DONE, but has no due date, so it is not overdue either.
                .andExpect(jsonPath("$.overdueByCategory.COMPLIANCE").value(0))
                .andExpect(jsonPath("$.overdueByCategory.AUDIT").value(0))
                .andExpect(jsonPath("$.overdueByCategory.GENERAL").value(0));
    }

    @Test
    @DisplayName("the rollup lists the blocked activity with its real priority and assignee")
    void rollupListsTheBlockedActivity() throws Exception {
        mockMvc.perform(get("/api/reports/region/region-north"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedActivities.length()").value(1))
                .andExpect(jsonPath("$.blockedActivities[0].taskId").value("task-004"))
                .andExpect(jsonPath("$.blockedActivities[0].storeId").value("store-002"))
                .andExpect(jsonPath("$.blockedActivities[0].title").value("Stockroom cage audit"))
                .andExpect(jsonPath("$.blockedActivities[0].category").value("AUDIT"))
                .andExpect(jsonPath("$.blockedActivities[0].priority").value("LOW"))
                .andExpect(jsonPath("$.blockedActivities[0].assigneeId").value("user-005"));
    }

    @Test
    @DisplayName("the rollup breaks the region down per store, sorted by store id")
    void rollupBreaksDownPerStore() throws Exception {
        mockMvc.perform(get("/api/reports/region/region-north"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeBreakdown.length()").value(2))
                .andExpect(jsonPath("$.storeBreakdown[0].storeId").value("store-001"))
                .andExpect(jsonPath("$.storeBreakdown[0].totalActivities").value(3))
                .andExpect(jsonPath("$.storeBreakdown[0].completedActivities").value(1))
                .andExpect(jsonPath("$.storeBreakdown[0].completionRate").value(0.3333))
                .andExpect(jsonPath("$.storeBreakdown[0].overdueCount").value(2))
                .andExpect(jsonPath("$.storeBreakdown[0].blockedCount").value(0))
                .andExpect(jsonPath("$.storeBreakdown[1].storeId").value("store-002"))
                .andExpect(jsonPath("$.storeBreakdown[1].totalActivities").value(1))
                .andExpect(jsonPath("$.storeBreakdown[1].completedActivities").value(0))
                .andExpect(jsonPath("$.storeBreakdown[1].completionRate").value(0.0))
                .andExpect(jsonPath("$.storeBreakdown[1].overdueCount").value(0))
                .andExpect(jsonPath("$.storeBreakdown[1].blockedCount").value(1));
    }

    @Test
    @DisplayName("an unknown region is a 404 REGION_NOT_FOUND, not an all-zero report")
    void unknownRegionIsNotFound() throws Exception {
        mockMvc.perform(get("/api/reports/region/region-atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    @DisplayName("a requestedBy naming nobody is a 404 USER_NOT_FOUND, and a real one is accepted")
    void requestedByIsValidatedAgainstTheStaffRoster() throws Exception {
        mockMvc.perform(get("/api/reports/region/region-north").param("requestedBy", "user-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(get("/api/reports/region/region-north").param("requestedBy", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeCount").value(2));
    }

    @Test
    @DisplayName("the GET delivers REGIONAL_ROLLUP_REQUESTED after commit, so the publisher is transactional")
    void theRollupEventIsDeliveredAfterCommit() throws Exception {
        subscriber.clear();

        mockMvc.perform(get("/api/reports/region/region-north").param("requestedBy", "user-001"))
                .andExpect(status().isOk());

        // This is the assertion that pins @Transactional on ReportService.regionalRollup. An
        // AFTER_COMMIT subscriber is skipped outright when no transaction is active, so an event
        // arriving here proves both that one existed and that it committed. RecordingEventBus
        // cannot prove this: it records at publish time and would look identical either way.
        assertThat(subscriber.received()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("REGIONAL_ROLLUP_REQUESTED");
            assertThat(event.regionId()).isEqualTo("region-north");
            assertThat(event.requestedBy()).isEqualTo("user-001");
            assertThat(event.storeCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a rejected request delivers no rollup event")
    void aRejectedRequestDeliversNoEvent() throws Exception {
        subscriber.clear();

        mockMvc.perform(get("/api/reports/region/region-atlantis"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reports/region/region-north").param("requestedBy", "user-999"))
                .andExpect(status().isNotFound());

        assertThat(subscriber.received()).isEmpty();
    }

    @Test
    @DisplayName("the GET persists one PENDING REGIONAL_ROLLUP report for the region")
    void theGetProducesAPendingReportRecord() throws Exception {
        assertThat(reportService.findByScopeId("region-north")).isEmpty();

        mockMvc.perform(get("/api/reports/region/region-north").param("requestedBy", "user-001"))
                .andExpect(status().isOk());

        // Asserted on the persisted row, never on the status: a 200 comes back whether or not the
        // listener fired, whether or not it was transactional, and whether or not it could write.
        assertThat(reportService.findByScopeId("region-north")).singleElement().satisfies(report -> {
            assertThat(report.reportType()).isEqualTo(ReportType.REGIONAL_ROLLUP);
            assertThat(report.status()).isEqualTo(ReportStatus.PENDING);
            assertThat(report.scopeId()).isEqualTo("region-north");
            assertThat(report.requestedBy()).isEqualTo("user-001");
            assertThat(report.requestedAt()).isNotNull();
            assertThat(report.readyAt()).isNull();
        });
    }

    @Test
    @DisplayName("two requests record two reports, so this is a log rather than an upsert")
    void twoRequestsRecordTwoReports() throws Exception {
        mockMvc.perform(get("/api/reports/region/region-north")).andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/region/region-north")).andExpect(status().isOk());

        final List<Report> reports = reportService.findByScopeId("region-north");
        assertThat(reports).hasSize(2);
        assertThat(reports).extracting(Report::id).doesNotHaveDuplicates();
        assertThat(reports).allSatisfy(report -> {
            assertThat(report.status()).isEqualTo(ReportStatus.PENDING);
            assertThat(report.reportType()).isEqualTo(ReportType.REGIONAL_ROLLUP);
            assertThat(report.requestedBy()).isEqualTo("api");
        });
    }

    @Test
    @DisplayName("a rejected request records no report at all")
    void aRejectedRequestRecordsNothing() throws Exception {
        mockMvc.perform(get("/api/reports/region/region-atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        mockMvc.perform(get("/api/reports/region/region-north").param("requestedBy", "user-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(reportService.findByScopeId("region-atlantis")).isEmpty();
        assertThat(reportService.findByScopeId("region-north")).isEmpty();
    }

    @Test
    @DisplayName("a rolled-back rollup records nothing, which is the point of after-commit dispatch")
    void aRolledBackRollupRecordsNothing() {
        transactionTemplate.executeWithoutResult(status -> {
            // regionalRollup is @Transactional with the default REQUIRED, so it joins this
            // transaction rather than starting its own. Rolling this back means no commit, and no
            // commit means the after-commit callback never runs.
            reportService.regionalRollup("region-north", "user-001");
            status.setRollbackOnly();
        });

        assertThat(reportService.findByScopeId("region-north")).isEmpty();
    }

    @Test
    @DisplayName("a failing subscriber breaks neither the caller's request nor the other subscriber")
    void aFailingSubscriberIsContained() throws Exception {
        final int failuresBefore = failingSubscriber.invocationCount();

        mockMvc.perform(get("/api/reports/region/region-north"))
                .andExpect(status().isOk());

        // Counted, so this cannot pass merely because dispatch was broken and nothing ran.
        assertThat(failingSubscriber.invocationCount()).isEqualTo(failuresBefore + 1);
        // And the real listener's row is still there: one broken subscriber does not suppress a
        // sibling on the same event. Remove the ErrorHandler bean from EventBusConfiguration and
        // the request above returns 500 instead.
        assertThat(reportService.findByScopeId("region-north"))
                .singleElement()
                .extracting(Report::reportType)
                .isEqualTo(ReportType.REGIONAL_ROLLUP);
    }

    @Test
    @DisplayName("the overdue category keys arrive in TaskCategory declaration order")
    void overdueCategoryKeysKeepTheirOrder() throws Exception {
        final String body = mockMvc.perform(get("/api/reports/region/region-north"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Asserted on the raw body rather than through JSONPath, because the guarantee is about the
        // serialised key order and a parsed map would hide it.
        assertThat(body).contains(
                "\"overdueByCategory\":{\"RESTOCKING\":1,\"PLANOGRAM\":1,\"AUDIT\":0,"
                        + "\"COMPLIANCE\":0,\"GENERAL\":0}");
    }

    @Test
    @DisplayName("the store summary for store-001 still reports its own figures unchanged")
    void storeSummaryIsUnaffected() throws Exception {
        mockMvc.perform(get("/api/reports/store/store-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActivities").value(3))
                .andExpect(jsonPath("$.completedActivities").value(1))
                .andExpect(jsonPath("$.overdueCount").value(2))
                // The store summary keeps its sparse category map; only the rollup pads with zeroes.
                .andExpect(jsonPath("$.overdueByCategory.length()").value(2));
    }
}
