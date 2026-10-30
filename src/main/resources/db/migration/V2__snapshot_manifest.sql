ALTER TABLE snapshot_runs
    ADD COLUMN expected_checksum char(64),
    ADD COLUMN uploaded_checksum char(64);
