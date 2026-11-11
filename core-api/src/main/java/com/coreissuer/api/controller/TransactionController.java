package com.coreissuer.api.controller;

import com.coreissuer.api.dto.AuthorizeRequest;
import com.coreissuer.api.dto.AuthorizeResponse;
import com.coreissuer.api.idempotency.IdempotencyService;
import com.coreissuer.api.service.TransactionService;
import com.coreissuer.common.util.CryptoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Validated
public class TransactionController {

    private static final String AUTHORIZE_ENDPOINT = "/api/v1/transactions/authorize";

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @PostMapping("/authorize")
    public ResponseEntity<AuthorizeResponse> authorize(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 80) String idempotencyKey,
            @Valid @RequestBody AuthorizeRequest request) {

        String requestHash = CryptoUtils.simpleHash(toJson(request));

        Optional<String> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey, requestHash);
        if (cachedResponse.isPresent()) {
            return ResponseEntity.ok(fromJson(cachedResponse.get()));
        }

        try {
            AuthorizeResponse response = transactionService.authorize(request);
            idempotencyService.saveResponse(idempotencyKey, AUTHORIZE_ENDPOINT, requestHash, 200, toJson(response));
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            // Release the in-flight marker so the client can safely retry,
            // then let GlobalExceptionHandler map the failure to a status code.
            idempotencyService.clearProcessing(idempotencyKey);
            throw e;
        }
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<Void> capture(@PathVariable String id) {
        transactionService.capture(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/reverse")
    public ResponseEntity<Void> reverse(@PathVariable String id) {
        transactionService.reverse(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Void> refund(@PathVariable String id) {
        transactionService.refund(id);
        return ResponseEntity.ok().build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to serialize payload", e);
        }
    }

    private AuthorizeResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, AuthorizeResponse.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize cached response", e);
        }
    }
}
