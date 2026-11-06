package com.devdesk.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;

import com.devdesk.agent.TicketRepository.TicketType;

/** Stores a digest of the canonical ticket fields rather than the request text a second time. */
final class TicketRequestFingerprint {

    private TicketRequestFingerprint() {}

    static String of(TicketType type, String resource, String reason) {
        String canonical = List.of(type == null ? "" : type.name(),
                        normalize(resource).toUpperCase(Locale.ROOT), normalize(reason))
                .stream().map(value -> value.length() + ":" + value)
                .collect(java.util.stream.Collectors.joining());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.trim().replaceAll("\\s+", " "), Normalizer.Form.NFC);
    }
}
