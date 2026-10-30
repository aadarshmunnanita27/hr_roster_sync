package com.moveinsync.roster.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class HrFingerprintService {
    public HrFingerprint fingerprint(EmployeeState state) {
        String canonical = String.join("\n",
                normalize(state.name()),
                normalize(state.email()),
                normalize(state.phone()),
                normalize(state.managerId()),
                normalize(state.officeCode()),
                normalize(state.shift()),
                Boolean.toString(state.active()));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new HrFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
