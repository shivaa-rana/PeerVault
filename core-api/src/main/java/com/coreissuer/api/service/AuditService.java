package com.coreissuer.api.service;

import com.coreissuer.common.domain.AuditLog;
import com.coreissuer.common.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, String action, String subjectId, Map<String, String> details) {
        AuditLog log = new AuditLog();
        log.setActorUserId(actorId);
        log.setAction(action);
        log.setSubjectId(subjectId);
        try {
            log.setDetails(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize audit details", e);
        }
        repository.save(log);
    }
}
