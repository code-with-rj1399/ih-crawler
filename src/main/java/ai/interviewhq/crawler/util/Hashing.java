package ai.interviewhq.crawler.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    public static String experienceDedupeHash(String sourcePlatform, String originalPostUrl) {
        return sha256Hex(normalize(sourcePlatform) + "\n" + normalize(originalPostUrl));
    }

    public static String questionDedupeHash(String company, String questionText) {
        return sha256Hex(normalize(company) + "\n" + normalize(questionText));
    }
}
