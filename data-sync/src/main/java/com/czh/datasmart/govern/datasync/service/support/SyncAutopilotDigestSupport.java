/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotDigestSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Creates stable low-sensitive SHA-256 bindings for authorization, policy, and receipt facts.
 *
 * <p>Only the digest is persisted in a recovery case. The source policy JSON, authorization
 * identifier, payload, SQL, and error body stay out of the case and receipt tables.</p>
 */
public final class SyncAutopilotDigestSupport {

    private SyncAutopilotDigestSupport() {
    }

    /**
     * Computes the canonical SHA-256 binding used by receipts, policy snapshots, and event identities.
     *
     * <p>The input is encoded as UTF-8 and {@code null} deliberately means the empty string, so every caller
     * has a deterministic digest instead of a null branch. The method is pure, idempotent, and has no I/O or
     * persistence side effect. A digest is an integrity/correlation value, not encryption: callers must still
     * avoid supplying credentials, SQL, raw records, or other sensitive source text.</p>
     *
     * @param value low-sensitive canonical text to bind; {@code null} is normalized to an empty string
     * @return a lowercase 64-character SHA-256 hexadecimal digest
     * @throws IllegalStateException if the JDK cannot provide its required SHA-256 implementation
     */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the JDK", exception);
        }
    }
}
