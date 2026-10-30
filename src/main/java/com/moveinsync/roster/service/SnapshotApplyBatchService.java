package com.moveinsync.roster.service;

import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.EventProcessingResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotApplyBatchService {
    private final EmployeeSyncService employeeSyncService;

    public SnapshotApplyBatchService(EmployeeSyncService employeeSyncService) {
        this.employeeSyncService = employeeSyncService;
    }

    @Transactional
    public List<EventProcessingResult> apply(TenantId tenantId, List<ValidatedHrEvent> events) {
        List<EventProcessingResult> results = new ArrayList<>(events.size());
        for (ValidatedHrEvent event : events) {
            results.add(employeeSyncService.process(tenantId, event));
        }
        return results;
    }
}
