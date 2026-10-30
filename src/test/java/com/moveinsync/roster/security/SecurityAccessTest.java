package com.moveinsync.roster.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.roster.config.CorrelationIdFilter;
import com.moveinsync.roster.config.SecurityConfig;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.service.HrEventService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {
        com.moveinsync.roster.controller.HrEventController.class,
        com.moveinsync.roster.controller.SnapshotController.class
})
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
@TestPropertySource(properties = "roster.jwt.secret=security-test-secret-of-at-least-32-bytes")
class SecurityAccessTest {
    private static final String SECRET = "security-test-secret-of-at-least-32-bytes";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HrEventService hrEventService;

    @MockBean
    private com.moveinsync.roster.service.SnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        when(hrEventService.process(any(TenantId.class), anyList())).thenReturn(List.of());
    }

    @Test
    void requiresAValidToken() throws Exception {
        mockMvc.perform(post("/api/v1/hr/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsTheHrRoleAndDeniesAnOperatorOnTheIngestEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/hr/events")
                        .header("Authorization", "Bearer " + token("HR_SOURCE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/hr/events")
                        .header("Authorization", "Bearer " + token("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/hr/snapshots/00000000-0000-4000-8000-000000000001/approve")
                        .header("Authorization", "Bearer " + token("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not authorized\"}"))
                .andExpect(status().isForbidden());
    }

    private String token(String role) throws Exception {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long expiry = java.time.Instant.now().plusSeconds(120).getEpochSecond();
        String claims = encode("{\"sub\":\"security-test\",\"tenant_id\":\"tenant-a\","
                + "\"roles\":[\"" + role + "\"],\"source_system\":\"test-hr\",\"exp\":" + expiry + "}");
        String signedValue = header + "." + claims;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signedValue.getBytes(StandardCharsets.US_ASCII)));
        return signedValue + "." + signature;
    }

    private String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
