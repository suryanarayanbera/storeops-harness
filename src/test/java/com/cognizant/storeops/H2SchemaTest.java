package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards the H2 schema Hibernate generates from the {@code @Entity} classes.
 *
 * <p>Worth having because {@code data.sql} is hand-written SQL: it references table and column names
 * that nothing else checks at compile time. Rename a field or a {@code @Column} and the seed script
 * silently stops matching - this test fails instead.
 */
@SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")
class H2SchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    private List<String> tableNames() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'PUBLIC' ORDER BY table_name",
                String.class);
    }

    private List<String> columnNames(final String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'PUBLIC' AND table_name = ? ORDER BY ordinal_position",
                String.class, table);
    }

    @Test
    @DisplayName("one table per aggregate, plus the programme membership collection table")
    void schemaHasExpectedTables() {
        assertThat(tableNames()).containsExactly(
                "NOTIFICATIONS", "PROJECTS", "PROJECT_MEMBERS", "REPORTS", "TASKS", "USERS");
    }

    @Test
    @DisplayName("tasks table carries every Task field as its own column")
    void tasksTableShape() {
        assertThat(columnNames("TASKS")).containsExactlyInAnyOrder(
                "ID", "TITLE", "DESCRIPTION", "STATUS", "PRIORITY", "CATEGORY",
                "STORE_ID", "PROJECT_ID", "ASSIGNEE_ID", "DUE_AT", "CREATED_AT", "UPDATED_AT");
    }

    @Test
    @DisplayName("UserProfile is flattened onto the users table rather than given its own")
    void usersTableFlattensProfile() {
        assertThat(columnNames("USERS")).contains("PHONE", "DEPARTMENT", "SHIFT_PATTERN");
    }

    @Test
    @DisplayName("programme membership is a collection table keyed by project_id")
    void projectMembersTableShape() {
        assertThat(columnNames("PROJECT_MEMBERS"))
                .containsExactlyInAnyOrder("PROJECT_ID", "USER_ID", "ROLE", "JOINED_AT");
    }

    /**
     * The seed rows named by {@code data.sql}, counted by id.
     *
     * <p>Counted by id rather than as a table total because the H2 database is shared for the whole
     * JVM ({@code DB_CLOSE_DELAY=-1}) while Spring caches one context per distinct test
     * configuration, so a table total makes this test a function of context build order. The seed ids
     * do not move.
     *
     * <p>{@code ApiSmokeTest} - which posts a programme and cannot remove it - now carries
     * {@code @DirtiesContext(AFTER_CLASS)}, so the totals would in fact balance today. Kept keyed on
     * ids anyway: that fix depends on one annotation on one class staying put, while this assertion
     * holds however many contexts the suite grows.
     */
    @Test
    @DisplayName("data.sql populated every table it names, and left reports empty")
    void seedDataLoaded() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id LIKE 'user-00%'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM projects WHERE id IN ('project-001', 'project-002')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_members WHERE project_id IN ('project-001', 'project-002')",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE id LIKE 'task-00%'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE id = 'notification-001'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("enums are stored as readable strings, not ordinals")
    void enumsStoredAsStrings() {
        assertThat(jdbc.queryForObject(
                "SELECT status FROM tasks WHERE id = 'task-004'", String.class)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject(
                "SELECT role FROM users WHERE id = 'user-001'", String.class)).isEqualTo("REGIONAL_MANAGER");
    }
}
