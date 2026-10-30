package com.moveinsync.roster.core;

public record HrFingerprint(String value) {
    public HrFingerprint {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Fingerprint must be a SHA-256 hex value");
        }
    }
}
