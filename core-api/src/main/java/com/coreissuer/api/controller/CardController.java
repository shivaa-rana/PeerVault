package com.coreissuer.api.controller;

import com.coreissuer.api.dto.CardResponse;
import com.coreissuer.api.dto.ProvisionCardRequest;
import com.coreissuer.api.service.CardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
@Validated
public class CardController {

    private final CardService cardService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse provisionCard(@Valid @RequestBody ProvisionCardRequest request) {
        return cardService.provisionCard(request);
    }

    @GetMapping("/{id}")
    public CardResponse getCard(@PathVariable String id) {
        return cardService.getCard(id);
    }
}
