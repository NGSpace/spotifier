package dev.ngspace.hudder.spotifier.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class Verifier {private Verifier() {}

	public static String generateCodeVerifier() {
        SecureRandom rnd = new SecureRandom();
        byte[] bytes = new byte[64];
        rnd.nextBytes(bytes);
        String v = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (v.length() < 43) v = v + "A".repeat(43 - v.length());
        if (v.length() > 128) v = v.substring(0, 128);
        return v;
    }

	public static String generateCodeChallenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new UnsupportedOperationException("SHA-256 not available", e);
        }
    }

	public static String randomUrlSafe(int bytesLen) {
        SecureRandom rnd = new SecureRandom();
        byte[] b = new byte[bytesLen];
        rnd.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
