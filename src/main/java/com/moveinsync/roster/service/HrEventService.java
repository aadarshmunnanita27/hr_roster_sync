package com.moveinsync.roster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.moveinsync.roster.core.EmployeeId;
import com.moveinsync.roster.core.EmployeeState;
import com.moveinsync.roster.core.EventType;
import com.moveinsync.roster.core.FieldOwnershipPolicy;
import com.moveinsync.roster.core.HrFingerprintService;
import com.moveinsync.roster.core.SourceVersion;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.EventProcessingResult;
import com.moveinsync.roster.dto.HrEventRequest;
import com.moveinsync.roster.exception.TenantAccessException;
import com.moveinsync.roster.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HrEventService {
    private static final Set<String> HR_FIELDS =
            Set.of("name", "email", "phone", "managerId", "officeCode", "shift", "active");
    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE =
            Pattern.compile("^[+0-9(). -]{7,24}$");
    private static final Pattern SHIFT =
            Pattern.compile("^[A-Za-z0-9 _-]{1,40}$");

    private final java.util.Map<EventType, HrEventHandler> handlers;
    private final FieldOwnershipPolicy ownershipPolicy;
    private final HrFingerprintService fingerprintService;
    private final JdbcTemplate jdbcTemplate;
    private final Cache<String, Boolean> officeCache;
    private final MeterRegistry meterRegistry;

    public HrEventService(java.util.List<HrEventHandler> handlers,
                          FieldOwnershipPolicy ownershipPolicy,
                          HrFingerprintService fingerprintService,
                          JdbcTemplate jdbcTemplate,
                          Cache<String, Boolean> officeCache,
                          MeterRegistry meterRegistry) {
        this.handlers = handlers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                HrEventHandler::eventType, handler -> handler));
        this.ownershipPolicy = ownershipPolicy;
        this.fingerprintService = fingerprintService;
        this.jdbcTemplate = jdbcTemplate;
        this.officeCache = officeCache;
        this.meterRegistry = meterRegistry;
    }

    public List<EventProcessingResult> process(TenantId tenantId, List<HrEventRequest> events) {
        return process(tenantId, null, events);
    }

    public List<EventProcessingResult> process(
            TenantId tenantId, String credentialSourceSystem, List<HrEventRequest> events) {
        List<EventProcessingResult> results = new ArrayList<>(events.size());
        for (HrEventRequest request : events) {
            meterRegistry.counter("hr_events_received_total").increment();
            if (request == null) {
                results.add(EventProcessingResult.rejected(null, "INVALID_EVENT", null));
                rejected("INVALID_EVENT");
                continue;
            }
            if (request.tenantId() != null && !request.tenantId().equals(tenantId.value())) {
                throw new TenantAccessException();
            }
            if (credentialSourceSystem != null
                    && !credentialSourceSystem.equals(request.sourceSystem())) {
                results.add(EventProcessingResult.rejected(
                        safeEmployeeId(request.employeeId()), "SOURCE_SYSTEM_CONFLICT", "sourceSystem"));
                rejected("SOURCE_SYSTEM_CONFLICT");
                continue;
            }

            try {
                ValidatedHrEvent event = validate(tenantId, request);
                EventProcessingResult result = handlers.get(event.eventType()).handle(tenantId, event);
                results.add(result);
                if ("APPLIED".equals(result.status())) {
                    meterRegistry.counter("hr_events_applied_total").increment();
                } else if ("DUPLICATE".equals(result.status())) {
                    meterRegistry.counter("hr_events_duplicate_total").increment();
                } else if ("STALE_IGNORED".equals(result.status())) {
                    meterRegistry.counter("hr_events_stale_total").increment();
                } else if ("REJECTED".equals(result.status())) {
                    rejected(result.errorCode());
                } else if ("VERSION_CONFLICT".equals(result.status())) {
                    rejected("VERSION_CONFLICT");
                }
            } catch (ValidationException exception) {
                results.add(EventProcessingResult.rejected(
                        safeEmployeeId(request.employeeId()), exception.errorCode(), exception.field()));
                rejected(exception.errorCode());
            }
        }
        return results;
    }

    public ValidatedHrEvent validateForSnapshot(TenantId tenantId, HrEventRequest request) {
        if (request.tenantId() != null && !request.tenantId().equals(tenantId.value())) {
            throw new TenantAccessException();
        }
        return validate(tenantId, request);
    }

    private ValidatedHrEvent validate(TenantId tenantId, HrEventRequest request) {
        if (request.employeeId() == null || request.employeeId().isBlank()) {
            throw new ValidationException("MISSING_REQUIRED_FIELD", "employeeId");
        }
        if (request.employeeId().length() > 100) {
            throw new ValidationException("INVALID_EMPLOYEE_ID", "employeeId");
        }
        if (request.sourceSystem() == null || request.sourceSystem().isBlank()) {
            throw new ValidationException("MISSING_REQUIRED_FIELD", "sourceSystem");
        }
        if (request.sourceSystem().length() > 100) {
            throw new ValidationException("INVALID_SOURCE_SYSTEM", "sourceSystem");
        }
        if (request.sourceVersion() == null || request.sourceVersion() < 0) {
            throw new ValidationException("INVALID_SOURCE_VERSION", "sourceVersion");
        }

        EventType eventType;
        try {
            eventType = EventType.valueOf(request.eventType());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ValidationException("INVALID_EVENT_TYPE", "eventType");
        }

        Map<String, Object> data = request.data() == null ? Map.of() : request.data();
        for (String field : data.keySet()) {
            String errorCode = ownershipPolicy.invalidField(field);
            if (errorCode != null) {
                throw new ValidationException(errorCode,
                        "UNKNOWN_FIELD".equals(errorCode) ? "data" : field);
            }
        }

        if (eventType == EventType.DEACTIVATE) {
            if (!data.isEmpty()) {
                throw new ValidationException("INVALID_DEACTIVATION_PAYLOAD", "data");
            }
            return new ValidatedHrEvent(new EmployeeId(request.employeeId()), request.sourceSystem(),
                    new SourceVersion(request.sourceVersion()), eventType, null, null);
        }

        for (String required : List.of("name", "email", "phone", "officeCode", "shift")) {
            if (!data.containsKey(required)) {
                throw new ValidationException("MISSING_REQUIRED_FIELD", required);
            }
        }

        String name = requiredString(data, "name");
        String email = requiredString(data, "email");
        String phone = requiredString(data, "phone");
        String officeCode = requiredString(data, "officeCode");
        String shift = requiredString(data, "shift");
        String managerId = optionalString(data, "managerId");
        boolean active = optionalBoolean(data, "active", true);

        if (name.length() > 200) {
            throw new ValidationException("INVALID_NAME", "name");
        }
        if (!EMAIL.matcher(email).matches() || email.length() > 254) {
            throw new ValidationException("INVALID_EMAIL", "email");
        }
        long phoneDigits = phone.chars().filter(Character::isDigit).count();
        if (!PHONE.matcher(phone).matches() || phoneDigits < 7 || phoneDigits > 15) {
            throw new ValidationException("INVALID_PHONE", "phone");
        }
        if (!SHIFT.matcher(shift).matches()) {
            throw new ValidationException("INVALID_SHIFT", "shift");
        }
        String officeKey = tenantId.value() + ":" + officeCode;
        boolean validOffice = officeCache.get(officeKey, key -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM offices WHERE tenant_id = ? AND office_code = ? AND active)",
                Boolean.class,
                tenantId.value(), officeCode)));
        if (!validOffice) {
            throw new ValidationException("UNKNOWN_OFFICE", "officeCode");
        }

        EmployeeState state = new EmployeeState(name, email, phone, managerId, officeCode, shift, active);
        return new ValidatedHrEvent(new EmployeeId(request.employeeId()), request.sourceSystem(),
                new SourceVersion(request.sourceVersion()), eventType, state,
                fingerprintService.fingerprint(state).value());
    }

    private String requiredString(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ValidationException(field.equals("name") ? "INVALID_NAME" : "MISSING_REQUIRED_FIELD", field);
        }
        return text.trim();
    }

    private String optionalString(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > 100) {
            throw new ValidationException("INVALID_FIELD", field);
        }
        return text.trim();
    }

    private boolean optionalBoolean(Map<String, Object> data, String field, boolean defaultValue) {
        Object value = data.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean result)) {
            throw new ValidationException("INVALID_FIELD", field);
        }
        return result;
    }

    private String safeEmployeeId(String employeeId) {
        return employeeId == null || employeeId.isBlank() || employeeId.length() > 100 ? null : employeeId;
    }

    private void rejected(String errorCode) {
        meterRegistry.counter("hr_events_rejected_total", "errorCode", errorCode).increment();
    }
}
