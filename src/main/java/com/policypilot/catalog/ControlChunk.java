package com.policypilot.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One retrievable unit: a single control or control enhancement. Chunking by control ID (rather
 * than by fixed token windows) means every retrieved chunk maps to exactly one citable ID.
 */
public record ControlChunk(
        String controlId,     // "AC-2(3)": the citation key
        String sortId,        // "ac-02.03"
        String family,        // "AC"
        String familyTitle,   // "Access Control"
        String parentId,      // "AC-2" for enhancements, null for base controls
        String title,
        String statement,
        String guidance) {

    /** Guidance can run to thousands of words; small embedding models only read the first few hundred tokens. */
    private static final int GUIDANCE_CHARS_FOR_EMBEDDING = 600;

    /** The text that gets embedded: what the control is and requires, plus the start of NIST's discussion. */
    public String embeddingText() {
        String g = guidance.length() > GUIDANCE_CHARS_FOR_EMBEDDING ? guidance.substring(0, GUIDANCE_CHARS_FOR_EMBEDDING) : guidance;
        return (controlId + " " + title + ". " + statement + " " + g).replaceAll("\\s+", " ").trim();
    }

    /** Fingerprint of the embedded text, so re-ingesting only re-embeds controls whose text changed. */
    public String contentHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(embeddingText().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
