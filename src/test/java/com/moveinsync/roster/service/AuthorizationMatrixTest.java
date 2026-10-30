package com.moveinsync.roster.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.roster.config.SecurityConfig;
import com.moveinsync.roster.controller.AdminController;
import com.moveinsync.roster.controller.EmployeeController;
import com.moveinsync.roster.controller.HrEventController;
import com.moveinsync.roster.controller.SnapshotController;
import com.moveinsync.roster.controller.TenantSettingsController;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.service.EmployeeService;
import com.moveinsync.roster.service.HrEventService;
import com.moveinsync.roster.service.OfficeService;
import com.moveinsync.roster.service.SnapshotService;
import com.moveinsync.roster.service.TenantSettingsService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authorization matrix: every protected endpoint is exercised with every role and with
 * no/invalid credentials, and the tenant identity is proven to come from the signed claim.
 * Runs without Docker.
 */
@WebMvcTest(controllers = {
        HrEventController.class,
        SnapshotController.class,
        EmployeeController.class,
        AdminController.class,
        TenantSettingsController.class
})
@Import(SecurityConfig.class)
@TestPropertySource(properties = "roster.jwt.secret=matrix-test-secret-of-at-least-32-bytes!!")
class AuthorizationMatrixTest {
    private static final String SECRET = "matrix-test-secret-of-at-least-32-bytes!!";
    private static final List<String> ROLES = List.of("HR_SOURCE", "TRANSPORT_ADMIN", "OPERATOR", "EMPLOYEE");
    private static final String RUN = "00000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HrEventService hrEventService;
    @MockBean
    private SnapshotService snapshotService;
    @MockBean
    private EmployeeService employeeService;
    @MockBean
    private OfficeService officeService;
    @MockBean
    private TenantSettingsService tenantSettingsService;

    /** (method, path, roles that are allowed). */
    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("POST", "/api/v1/hr/events", List.of("HR_SOURCE")),
                Arguments.of("POST", "/api/v1/hr/snapshots", List.of("HR_SOURCE")),
                Arguments.of("GET", "/api/v1/hr/snapshots/" + RUN, List.of("OPERATOR")),
                Arguments.of("GET", "/api/v1/hr/snapshots/" + RUN + "/errors", List.of("OPERATOR")),
                Arguments.of("POST", "/api/v1/hr/snapshots/" + RUN + "/approve", List.of("OPERATOR")),
                Arguments.of("POST", "/api/v1/hr/snapshots/" + RUN + "/reject", List.of("OPERATOR")),
                Arguments.of("PUT", "/api/v1/employees/E1/transport-preferences", List.of("TRANSPORT_ADMIN")),
                Arguments.of("GET", "/api/v1/employees/E1", List.of("EMPLOYEE", "TRANSPORT_ADMIN", "OPERATOR")),
                Arguments.of("GET", "/api/v1/admin/offices", List.of("TRANSPORT_ADMIN")),
                Arguments.of("PUT", "/api/v1/admin/offices", List.of("TRANSPORT_ADMIN")),
                Arguments.of("DELETE", "/api/v1/admin/offices/BLR01", List.of("TRANSPORT_ADMIN")),
                Arguments.of("GET", "/api/v1/admin/reconciliation-settings", List.of("TRANSPORT_ADMIN")),
                Arguments.of("PUT", "/api/v1/admin/reconciliation-settings", List.of("TRANSPORT_ADMIN")));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void everyRoleExceptTheAllowedOnesIsForbiddenAndAnonymousIsUnauthorized(
            String method, String path, List<String> allowedRoles) throws Exception {
        mockMvc.perform(request(method, path)).andExpect(status().isUnauthorized());

        for (String role : ROLES) {
            int code = mockMvc.perform(request(method, path)
                            .header("Authorization", "Bearer " + token("tenant-a", role, "src", "E1", 300, SECRET)))
                    .andReturn().getResponse().getStatus();
            if (allowedRoles.contains(role)) {
                assertEquals(false, code == 401 || code == 403,
                        role + " must reach " + method + " " + path + " but got " + code);
            } else {
                assertEquals(403, code, role + " must be forbidden on " + method + " " + path);
            }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void expiredTamperedAndForeignlySignedTokensAreRejected(
            String method, String path, List<String> allowedRoles) throws Exception {
        String role = allowedRoles.get(0);
        String expired = token("tenant-a", role, "src", "E1", -600, SECRET);
        String foreignKey = token("tenant-a", role, "src", "E1", 300, "another-secret-of-at-least-32-bytes-long");
        String valid = token("tenant-a", role, "src", "E1", 300, SECRET);
        String tampered = valid.substring(0, valid.length() - 2)
                + (valid.endsWith("AA") ? "BB" : "AA");

        for (String bad : List.of(expired, foreignKey, tampered)) {
            mockMvc.perform(request(method, path).header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void tenantIdentityComesFromTheSignedClaimNotTheRequest() throws Exception {
        when(hrEventService.process(any(TenantId.class), anyString(), anyList())).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/hr/events")
                        .header("Authorization", "Bearer " + token("tenant-a", "HR_SOURCE", "test-hr", null, 300, SECRET))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"E1\",\"sourceSystem\":\"test-hr\",\"sourceVersion\":1,"
                                + "\"eventType\":\"DEACTIVATE\",\"data\":{}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<TenantId> tenant = ArgumentCaptor.forClass(TenantId.class);
        verify(hrEventService).process(tenant.capture(), eq("test-hr"), anyList());
        assertEquals("tenant-a", tenant.getValue().value());
    }

    @Test
    void aTokenWithoutATenantClaimIsDeniedBeforeAnyServiceIsCalled() throws Exception {
        mockMvc.perform(post("/api/v1/hr/events")
                        .header("Authorization", "Bearer " + token(null, "HR_SOURCE", "test-hr", null, 300, SECRET))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_ACCESS_DENIED"));
        verify(hrEventService, never()).process(any(TenantId.class), anyString(), anyList());

        mockMvc.perform(get("/api/v1/admin/offices")
                        .header("Authorization", "Bearer " + token(null, "TRANSPORT_ADMIN", null, null, 300, SECRET)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_ACCESS_DENIED"));
    }

    @Test
    void anHrTokenWithoutASourceSystemCannotIngest() throws Exception {
        mockMvc.perform(post("/api/v1/hr/events")
                        .header("Authorization", "Bearer " + token("tenant-a", "HR_SOURCE", null, null, 300, SECRET))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"E1\"}"))
                .andExpect(status().isForbidden());
        verify(hrEventService, never()).process(any(TenantId.class), anyString(), anyList());
    }

    @Test
    void anEmployeeCanOnlyReadTheirOwnRecordInTheirOwnTenant() throws Exception {
        String token = token("tenant-a", "EMPLOYEE", null, "E1", 300, SECRET);

        mockMvc.perform(get("/api/v1/employees/E2").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_ACCESS_DENIED"));
        verify(employeeService, never()).getEmployee(anyString(), eq("E2"));

        mockMvc.perform(get("/api/v1/employees/E1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(employeeService).getEmployee("tenant-a", "E1");
    }

    @Test
    void operatorsOnlyReachRunsThroughTheirOwnTenant() throws Exception {
        mockMvc.perform(get("/api/v1/hr/snapshots/" + RUN)
                        .header("Authorization", "Bearer " + token("tenant-b", "OPERATOR", null, null, 300, SECRET)))
                .andReturn();

        ArgumentCaptor<TenantId> tenant = ArgumentCaptor.forClass(TenantId.class);
        verify(snapshotService).getRun(tenant.capture(), eq(UUID.fromString(RUN)));
        assertEquals("tenant-b", tenant.getValue().value());
    }

    @Test
    void anHrSourceCannotUploadASnapshotOnBehalfOfAnotherSourceSystem() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/hr/snapshots")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "s.csv", "text/csv", "x".getBytes(StandardCharsets.UTF_8)))
                        .param("sourceSystem", "someone-elses-hr")
                        .param("snapshotCutoff", "2026-01-01T00:00:00Z")
                        .header("Authorization", "Bearer " + token("tenant-a", "HR_SOURCE", "my-hr", null, 300, SECRET)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_ACCESS_DENIED"));
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder request(String method, String path) {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        return builder.contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private static String token(String tenant, String role, String source, String employeeId,
                                long secondsToExpiry, String secret) throws Exception {
        StringBuilder claims = new StringBuilder("{\"sub\":\"matrix-test\",\"roles\":[\"" + role + "\"]");
        if (tenant != null) {
            claims.append(",\"tenant_id\":\"").append(tenant).append("\"");
        }
        if (source != null) {
            claims.append(",\"source_system\":\"").append(source).append("\"");
        }
        if (employeeId != null) {
            claims.append(",\"employee_id\":\"").append(employeeId).append("\"");
        }
        claims.append(",\"exp\":").append(Instant.now().plusSeconds(secondsToExpiry).getEpochSecond()).append("}");

        String signedValue = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + encode(claims.toString());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signedValue.getBytes(StandardCharsets.US_ASCII)));
        return signedValue + "." + signature;
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
