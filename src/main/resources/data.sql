-- StoreOps seed data.
--
-- Runs after Hibernate creates the schema from the @Entity classes
-- (spring.jpa.defer-datasource-initialization=true). Timestamps are fixed rather than relative to
-- now() so that curl examples, the reports aggregation and the integration tests stay reproducible
-- across restarts.
--
-- Cross-references are by id and are not enforced by foreign keys: renaming a user id here means
-- updating the tasks and project_members rows that name it.

-- staff (users) ------------------------------------------------------------------------
INSERT INTO users (id, email, display_name, role, store_id, region_id, active, phone, department, shift_pattern, created_at) VALUES
  ('user-001', 'rita.shaw@storeops.example',    'Rita Shaw',    'REGIONAL_MANAGER', 'store-001', 'region-north', TRUE, '+44 20 7000 0001', 'OPERATIONS', 'EARLY', TIMESTAMP WITH TIME ZONE '2026-01-06 08:00:00+00'),
  ('user-002', 'sam.okafor@storeops.example',   'Sam Okafor',   'STORE_MANAGER',    'store-001', 'region-north', TRUE, '+44 20 7000 0002', 'OPERATIONS', 'EARLY', TIMESTAMP WITH TIME ZONE '2026-01-06 08:00:00+00'),
  ('user-003', 'lena.brandt@storeops.example',  'Lena Brandt',  'DEPARTMENT_LEAD',  'store-001', 'region-north', TRUE, '+44 20 7000 0003', 'GROCERY',    'LATE',  TIMESTAMP WITH TIME ZONE '2026-01-06 08:00:00+00'),
  ('user-004', 'tom.reilly@storeops.example',   'Tom Reilly',   'ASSOCIATE',        'store-001', 'region-north', TRUE, '+44 20 7000 0004', 'GROCERY',    'LATE',  TIMESTAMP WITH TIME ZONE '2026-01-06 08:00:00+00'),
  ('user-005', 'ana.silva@storeops.example',    'Ana Silva',    'STORE_MANAGER',    'store-002', 'region-north', TRUE, '+44 20 7000 0005', 'OPERATIONS', 'EARLY', TIMESTAMP WITH TIME ZONE '2026-01-06 08:00:00+00');

-- programmes (projects) ---------------------------------------------------------------
INSERT INTO projects (id, name, description, status, store_id, region_id, owner_id, created_at, closed_at) VALUES
  ('project-001', 'Spring seasonal rollout', 'Aisle resets and promotional planograms', 'ACTIVE',  'store-001', 'region-north', 'user-002', TIMESTAMP WITH TIME ZONE '2026-01-05 09:00:00+00', NULL),
  ('project-002', 'Q1 compliance drive',     'Chilled and stockroom compliance sweep',  'PLANNED', 'store-002', 'region-north', 'user-005', TIMESTAMP WITH TIME ZONE '2026-01-05 09:02:00+00', NULL);

INSERT INTO project_members (project_id, user_id, role, joined_at) VALUES
  ('project-001', 'user-002', 'STORE_MANAGER',   TIMESTAMP WITH TIME ZONE '2026-01-05 09:00:00+00'),
  ('project-001', 'user-003', 'DEPARTMENT_LEAD', TIMESTAMP WITH TIME ZONE '2026-01-05 09:00:00+00'),
  ('project-001', 'user-004', 'ASSOCIATE',       TIMESTAMP WITH TIME ZONE '2026-01-05 09:00:00+00'),
  ('project-002', 'user-005', 'STORE_MANAGER',   TIMESTAMP WITH TIME ZONE '2026-01-05 09:02:00+00');

-- activities (tasks) ------------------------------------------------------------------
-- task-001 and task-002 are past due and not DONE, so the store-001 summary reports 2 overdue.
INSERT INTO tasks (id, title, description, status, priority, category, store_id, project_id, assignee_id, due_at, created_at, updated_at) VALUES
  ('task-001', 'Restock aisle 4 beverages',           'Weekend promotion overflow',    'TODO',        'HIGH',     'RESTOCKING', 'store-001', 'project-001', 'user-004', TIMESTAMP WITH TIME ZONE '2026-01-07 08:00:00+00', TIMESTAMP WITH TIME ZONE '2026-01-06 08:00:00+00', TIMESTAMP WITH TIME ZONE '2026-01-06 08:00:00+00'),
  ('task-002', 'Reset seasonal planogram bay 12',     'Spring layout rollout',         'IN_PROGRESS', 'MEDIUM',   'PLANOGRAM',  'store-001', 'project-001', 'user-003', TIMESTAMP WITH TIME ZONE '2026-01-08 08:00:00+00', TIMESTAMP WITH TIME ZONE '2026-01-06 08:01:00+00', TIMESTAMP WITH TIME ZONE '2026-01-06 08:01:00+00'),
  ('task-003', 'Chilled temperature compliance check', 'Twice-daily log',              'DONE',        'CRITICAL', 'COMPLIANCE', 'store-001', NULL,          'user-003', TIMESTAMP WITH TIME ZONE '2026-01-06 09:00:00+00', TIMESTAMP WITH TIME ZONE '2026-01-06 08:02:00+00', TIMESTAMP WITH TIME ZONE '2026-01-06 10:00:00+00'),
  ('task-004', 'Stockroom cage audit',                 'Quarterly shrinkage audit',    'BLOCKED',     'LOW',      'AUDIT',      'store-002', 'project-002', 'user-005', NULL,                                              TIMESTAMP WITH TIME ZONE '2026-01-06 08:03:00+00', TIMESTAMP WITH TIME ZONE '2026-01-06 08:03:00+00');

-- alerts (notifications) --------------------------------------------------------------
INSERT INTO notifications (id, recipient_id, alert_type, channel, status, subject, body, source_ref, created_at, sent_at) VALUES
  ('notification-001', 'user-003', 'SHIFT_HANDOVER', 'IN_APP', 'SENT', 'Shift handover checklist outstanding', 'Two chilled compliance checks remain open for the late shift.', 'task-003', TIMESTAMP WITH TIME ZONE '2026-01-06 09:30:00+00', TIMESTAMP WITH TIME ZONE '2026-01-06 09:30:05+00');

-- reports: intentionally empty. Rows appear when a report is requested or a programme closes.
--
-- sla_breaches: intentionally empty, for the same reason. A row appears when the overdue sweep finds a
-- HIGH or CRITICAL activity past its due date and the alerts module tells somebody about it. Seeding one
-- would mean seeding a breach nobody was ever notified of, and would suppress the alert for that
-- activity - task-001 is overdue in the seed data, so its first sweep is what opens its episode.
