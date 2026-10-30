CREATE UNIQUE INDEX snapshot_errors_run_employee_code_idx
    ON snapshot_errors (run_id, employee_id, error_code);

CREATE INDEX employees_tenant_source_active_idx
    ON employees (tenant_id, hr_source_system, active);
