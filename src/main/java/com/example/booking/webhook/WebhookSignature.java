package com.example.booking.webhook;

import com.example.booking.common.exception.InvalidSignatureException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Header format: "t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + rawBody)>".
 * The timestamp is inside the signed text, so a captured request cannot be replayed later with a fresh timestamp.
 */
public final class WebhookSignature {
    private WebhookSignature() {}

    public static String header(String secret, long timestamp, String rawBody) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + rawBody);
    }

    public static void verify(String secret, String header, String rawBody, long toleranceSeconds, Instant now) {
        if (header == null) throw new InvalidSignatureException();
        String t = null, v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) t = kv[1];
            if (kv.length == 2 && kv[0].equals("v1")) v1 = kv[1];
        }
        if (t == null || v1 == null) throw new InvalidSignatureException();

        long timestamp;
        try { timestamp = Long.parseLong(t); } catch (NumberFormatException e) { throw new InvalidSignatureException(); }
        if (Math.abs(now.getEpochSecond() - timestamp) > toleranceSeconds) throw new InvalidSignatureException();

        String expected = hmac(secret, timestamp + "." + rawBody);
        // constant-time comparison: do not leak how many leading characters were correct
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidSignatureException();
        }
    }

    private static String hmac(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
