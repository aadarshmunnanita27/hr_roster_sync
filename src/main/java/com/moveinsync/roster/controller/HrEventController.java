package com.moveinsync.roster.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.EventProcessingResult;
import com.moveinsync.roster.dto.HrEventRequest;
import com.moveinsync.roster.exception.TenantAccessException;
import com.moveinsync.roster.security.CurrentActor;
import com.moveinsync.roster.service.HrEventService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/events")
public class HrEventController {
    private final HrEventService hrEventService;
    private final ObjectMapper objectMapper;

    public HrEventController(HrEventService hrEventService, ObjectMapper objectMapper) {
        this.hrEventService = hrEventService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public List<EventProcessingResult> ingest(
            @RequestBody JsonNode body, Authentication authentication) {
        CurrentActor actor = CurrentActor.from(authentication);
        if (actor.tenantId() == null || actor.tenantId().length() > 80) {
            throw new TenantAccessException();
        }

        List<HrEventRequest> events = new ArrayList<>();
        if (body.isArray()) {
            body.forEach(node -> events.add(toEvent(node)));
        } else if (body.isObject()) {
            events.add(toEvent(body));
        } else {
            throw new com.moveinsync.roster.exception.ValidationException("INVALID_EVENT", null);
        }
        if (actor.sourceSystem() == null || actor.sourceSystem().isBlank()) {
            throw new TenantAccessException();
        }
        return hrEventService.process(new TenantId(actor.tenantId()), actor.sourceSystem(), events);
    }

    private HrEventRequest toEvent(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, HrEventRequest.class);
        } catch (JsonProcessingException exception) {
            if (!node.isObject()) {
                return null;
            }
            String employeeId = node.path("employeeId").isTextual()
                    ? node.path("employeeId").asText() : null;
            String sourceSystem = node.path("sourceSystem").isTextual()
                    ? node.path("sourceSystem").asText() : null;
            String tenantId = node.path("tenantId").isTextual()
                    ? node.path("tenantId").asText() : null;
            return new HrEventRequest(tenantId, employeeId, sourceSystem, null,
                    null, java.util.Map.of());
        }
    }
}
