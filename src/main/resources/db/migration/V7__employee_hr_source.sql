ALTER TABLE employees
    ADD COLUMN hr_source_system varchar(100) NOT NULL DEFAULT 'unknown';

UPDATE employees
SET hr_source_system = 'demo-hr'
WHERE tenant_id = 'demo-tenant' AND employee_id = 'DEMO-EMPLOYEE';
