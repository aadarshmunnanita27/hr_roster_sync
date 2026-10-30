package com.moveinsync.roster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.moveinsync.roster.dto.OfficeRequest;
import com.moveinsync.roster.dto.OfficeResponse;
import com.moveinsync.roster.exception.ResourceNotFoundException;
import com.moveinsync.roster.repository.OfficeRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OfficeService {
    private final OfficeRepository officeRepository;
    private final Cache<String, Boolean> officeCache;

    public OfficeService(OfficeRepository officeRepository, Cache<String, Boolean> officeCache) {
        this.officeRepository = officeRepository;
        this.officeCache = officeCache;
    }

    public List<OfficeResponse> list(String tenantId) {
        return officeRepository.findAllByTenant(tenantId);
    }

    public void save(String tenantId, OfficeRequest request) {
        officeRepository.save(tenantId, request);
        officeCache.invalidate(tenantId + ":" + request.officeCode());
    }

    public void deactivate(String tenantId, String officeCode) {
        if (!officeRepository.deactivate(tenantId, officeCode)) {
            throw new ResourceNotFoundException("OFFICE_NOT_FOUND");
        }
        officeCache.invalidate(tenantId + ":" + officeCode);
    }
}
