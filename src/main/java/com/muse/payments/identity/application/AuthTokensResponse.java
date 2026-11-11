package com.muse.payments.identity.application;

public record AuthTokensResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
    public static AuthTokensResponse of(String accessToken, String refreshToken, long expiresIn) {
        return new AuthTokensResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
