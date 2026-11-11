package com.coreissuer.api.dto;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
}
