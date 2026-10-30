ALTER TABLE snapshot_runs
    ADD COLUMN unchanged_employee_count bigint,
    ADD COLUMN updated_employee_count bigint,
    ADD COLUMN new_employee_count bigint,
    ADD COLUMN stale_employee_count bigint;
