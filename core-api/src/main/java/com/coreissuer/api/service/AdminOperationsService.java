package com.coreissuer.api.service;

import com.coreissuer.api.dto.FraudFlagResponse;
import com.coreissuer.api.exception.ResourceNotFoundException;
import com.coreissuer.common.domain.FraudFlag;
import com.coreissuer.common.repository.AuditLogRepository;
import com.coreissuer.common.repository.FraudFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminOperationsService {
    private final FraudFlagRepository fraudFlags;
    private final AuditLogRepository auditLogs;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<FraudFlagResponse> fraudFlags(int page, int size) {
        return fraudFlags.findAll(PageRequest.of(page, size)).map(AdminOperationsService::toResponse);
    }

    @Transactional
    public FraudFlagResponse review(UUID flagId, String status, UUID reviewerId) {
        if (!"REVIEWED".equals(status) && !"DISMISSED".equals(status)) {
            throw new IllegalArgumentException("Review status must be REVIEWED or DISMISSED");
        }
        FraudFlag flag = fraudFlags.findById(flagId)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud flag not found"));
        if (!"OPEN".equals(flag.getReviewStatus())) {
            throw new IllegalStateException("Fraud flag has already been reviewed");
        }
        flag.setReviewStatus(status);
        auditService.record(reviewerId, "FRAUD_FLAG_REVIEWED", flagId.toString(), Map.of("status", status));
        return toResponse(fraudFlags.save(flag));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.coreissuer.common.domain.AuditLog> auditLogs(int page, int size) {
        return auditLogs.findAll(PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }

    private static FraudFlagResponse toResponse(FraudFlag flag) {
        return new FraudFlagResponse(flag.getId(), flag.getUserId(), flag.getTransferId(),
                flag.getRiskReason(), flag.getSeverity(), flag.getReviewStatus(), flag.getCreatedAt());
    }
}
