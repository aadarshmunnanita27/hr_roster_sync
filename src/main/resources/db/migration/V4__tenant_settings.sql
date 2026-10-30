CREATE TABLE tenant_settings (
    tenant_id varchar(80) PRIMARY KEY,
    safety_threshold_percent numeric(7, 3) NOT NULL
        CHECK (safety_threshold_percent >= 0 AND safety_threshold_percent <= 100),
    updated_at timestamptz NOT NULL DEFAULT now()
);
