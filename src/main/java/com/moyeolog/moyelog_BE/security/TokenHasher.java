package com.moyeolog.moyelog_BE.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TokenHasher {

    private TokenHasher() {
    }

    /** SHA-256 해시의 16진수 문자열(64자)을 반환합니다. */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** 길이/내용에 따른 시간 차이가 드러나지 않도록 두 문자열을 비교합니다. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(a).getBytes(StandardCharsets.UTF_8), sha256(b).getBytes(StandardCharsets.UTF_8));
    }
}
