INSERT INTO employees
    (tenant_id, employee_id, name, email, phone, manager_id, office_code, shift, active,
     hr_version, hr_hash, hr_updated_at, created_at, updated_at)
VALUES
    ('demo-tenant', 'DEMO-EMPLOYEE', 'Example Worker', 'worker@example.invalid',
     '+10000000000', 'MGR-1', 'BLR01', 'DAY', true, 0,
     repeat('0', 64), now(), now(), now());

INSERT INTO bookings (booking_id, tenant_id, employee_id, seat_id, status, scheduled_at)
VALUES ('00000000-0000-4000-8000-000000000101', 'demo-tenant', 'DEMO-EMPLOYEE',
        '00000000-0000-4000-8000-000000000201', 'CONFIRMED', now() + interval '1 day');

INSERT INTO cab_seats (seat_id, tenant_id, booking_id, occupied)
VALUES ('00000000-0000-4000-8000-000000000201', 'demo-tenant',
        '00000000-0000-4000-8000-000000000101', true);
