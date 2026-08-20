package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.staff.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The context loads and every module's service is wired. */
@SpringBootTest
class StoreOpsApplicationTests {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private EventBus eventBus;

    @Test
    @DisplayName("all five module services and the event bus are present in the context")
    void contextLoads() {
        assertThat(taskService).isNotNull();
        assertThat(projectService).isNotNull();
        assertThat(userService).isNotNull();
        assertThat(notificationService).isNotNull();
        assertThat(reportService).isNotNull();
        assertThat(eventBus).isNotNull();
    }

    @Test
    @DisplayName("seed data is loaded so a cold boot has something to serve")
    void seedDataIsLoaded() {
        assertThat(taskService.list(null, null, null, null)).isNotEmpty();
        assertThat(projectService.list(null, null)).isNotEmpty();
        assertThat(userService.findAll()).isNotEmpty();
        assertThat(notificationService.list(null, null)).isNotEmpty();
    }
}
