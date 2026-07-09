package vn.essvn.erpcafe.identity.application;

/** A freshly issued access token + refresh token. */
public record TokenPair(String accessToken, String refreshToken) {
}
