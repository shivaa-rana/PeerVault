package com.coreissuer.api.controller;

import com.coreissuer.api.dto.TransferRequest;
import com.coreissuer.api.dto.TransferResponse;
import com.coreissuer.api.security.AuthenticatedUser;
import com.coreissuer.api.service.WalletTransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
@Validated
public class WalletTransferController {
    private final WalletTransferService service;

    @PostMapping
    public TransferResponse transfer(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 80) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return service.transfer(user.id(), idempotencyKey, request);
    }

    @GetMapping
    public Page<TransferResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        WalletController.validatePage(page, size);
        return service.list(user.id(), page, size);
    }

    @GetMapping("/{id}")
    public TransferResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.get(user.id(), id);
    }
}
