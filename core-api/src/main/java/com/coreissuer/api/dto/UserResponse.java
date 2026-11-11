package com.coreissuer.api.dto;

import java.util.UUID;

public record UserResponse(UUID id, String email, String fullName, String role) {
}
