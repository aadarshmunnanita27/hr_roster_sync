package com.moveinsync.roster.service;

import com.moveinsync.roster.dto.EmployeeResponse;
import com.moveinsync.roster.dto.TransportPreferencesRequest;
import com.moveinsync.roster.exception.ResourceNotFoundException;
import com.moveinsync.roster.repository.EmployeeRepository;
import org.springframework.stereotype.Service;

@Service
public class EmployeeService {
    private final EmployeeRepository employeeRepository;

    public EmployeeService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    public EmployeeResponse getEmployee(String tenantId, String employeeId) {
        return employeeRepository.findByTenantAndId(tenantId, employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("EMPLOYEE_NOT_FOUND"));
    }

    public void updateTransportPreferences(
            String tenantId, String employeeId, TransportPreferencesRequest request) {
        if (!employeeRepository.updateTransportPreferences(tenantId, employeeId, request)) {
            throw new ResourceNotFoundException("EMPLOYEE_NOT_FOUND");
        }
    }
}
