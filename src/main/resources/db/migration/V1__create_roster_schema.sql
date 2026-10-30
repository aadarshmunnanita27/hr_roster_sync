CREATE TABLE offices (
    tenant_id varchar(80) NOT NULL,
    office_code varchar(40) NOT NULL,
    office_name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, office_code)
);

CREATE TABLE employees (
    tenant_id varchar(80) NOT NULL,
    employee_id varchar(100) NOT NULL,
    name varchar(200) NOT NULL,
    email varchar(254) NOT NULL,
    phone varchar(40) NOT NULL,
    manager_id varchar(100),
    office_code varchar(40) NOT NULL,
    shift varchar(40) NOT NULL,
    pickup_point varchar(200),
    special_assistance boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    hr_version bigint NOT NULL,
    hr_hash char(64) NOT NULL,
    hr_updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, employee_id),
    CONSTRAINT employees_office_fk FOREIGN KEY (tenant_id, office_code)
        REFERENCES offices (tenant_id, office_code)
);

CREATE INDEX employees_tenant_active_idx ON employees (tenant_id, active);

CREATE TABLE hr_event_inbox (
    tenant_id varchar(80) NOT NULL,
    source_system varchar(100) NOT NULL,
    employee_id varchar(100) NOT NULL,
    source_version bigint NOT NULL,
    fingerprint char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_system, employee_id, source_version)
);

CREATE TABLE outbox_events (
    event_id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    employee_id varchar(100) NOT NULL,
    event_type varchar(60) NOT NULL,
    source_version bigint NOT NULL,
    payload jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    last_error_code varchar(80)
);

CREATE INDEX outbox_pending_idx ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE TABLE snapshot_runs (
    run_id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    source_system varchar(100) NOT NULL,
    snapshot_cutoff timestamptz NOT NULL,
    expected_row_count bigint,
    uploaded_row_count bigint NOT NULL DEFAULT 0,
    state varchar(30) NOT NULL,
    active_employee_count bigint,
    snapshot_employee_count bigint,
    missing_employee_count bigint,
    missing_percentage numeric(7, 3),
    safety_threshold_percent numeric(7, 3),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE snapshot_staging (
    run_id uuid NOT NULL REFERENCES snapshot_runs(run_id) ON DELETE CASCADE,
    tenant_id varchar(80) NOT NULL,
    employee_id varchar(100) NOT NULL,
    source_version bigint NOT NULL,
    name varchar(200) NOT NULL,
    email varchar(254) NOT NULL,
    phone varchar(40) NOT NULL,
    manager_id varchar(100),
    office_code varchar(40) NOT NULL,
    shift varchar(40) NOT NULL,
    fingerprint char(64) NOT NULL,
    valid boolean NOT NULL DEFAULT true,
    PRIMARY KEY (run_id, tenant_id, employee_id)
);

CREATE INDEX snapshot_staging_tenant_employee_idx
    ON snapshot_staging (tenant_id, employee_id, run_id);

CREATE TABLE snapshot_errors (
    error_id bigserial PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES snapshot_runs(run_id) ON DELETE CASCADE,
    employee_id varchar(100),
    error_code varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
    audit_id bigserial PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    run_id uuid NOT NULL REFERENCES snapshot_runs(run_id),
    operator_id varchar(120) NOT NULL,
    decision varchar(20) NOT NULL,
    reason varchar(500) NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE bookings (
    booking_id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    employee_id varchar(100) NOT NULL,
    seat_id uuid NOT NULL,
    status varchar(30) NOT NULL,
    scheduled_at timestamptz NOT NULL
);

CREATE INDEX bookings_employee_future_idx
    ON bookings (tenant_id, employee_id, scheduled_at)
    WHERE status = 'CONFIRMED';

CREATE TABLE cab_seats (
    seat_id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    booking_id uuid UNIQUE,
    occupied boolean NOT NULL DEFAULT false
);

CREATE TABLE processed_messages (
    message_id uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO offices (tenant_id, office_code, office_name)
VALUES ('demo-tenant', 'BLR01', 'Bengaluru Central'),
       ('demo-tenant', 'BLR02', 'Bengaluru North');
