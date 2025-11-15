package dev.ngspace.hudder.spotifier.auth;

import org.json.JSONObject;

public record SpotifyToken(String accessToken, String refreshToken, int expiresIn, String tokenType, String scope) {
	public static SpotifyToken fromJSONObject(JSONObject json) {
        String access = json.optString("access_token", null);
        String refresh = json.optString("refresh_token", null);
        int expires = json.optInt("expires_in", 0);
        String tokenType = json.optString("token_type", "Bearer");
        String scope = json.optString("scope", "");
        return new SpotifyToken(access, refresh, expires, tokenType, scope);
    }
}
