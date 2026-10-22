package io.github.sanskar121543.reconciliation.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApiKeys {

    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    private final List<Entry> entries;

    public ApiKeys(@Value("${matching.security.api-keys}") String config) {
        this.entries = parse(config);
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "matching.security.api-keys must contain at least one clientId:role:sha256hex entry");
        }
    }

    public Optional<ApiCaller> callerFor(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        byte[] presentedHash = sha256(presentedKey);
        ApiCaller match = null;
        // Fixed-length hashes + isEqual keep the comparison constant-time, and we never stop early.
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(presentedHash, entry.hash())) {
                match = entry.caller();
            }
        }
        return Optional.ofNullable(match);
    }

    static List<Entry> parse(String config) {
        List<Entry> result = new ArrayList<>();
        if (config == null) {
            return result;
        }
        for (String item : config.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":");
            if (parts.length != 3 || parts[0].isBlank()) {
                throw new IllegalStateException("API key entries must look like clientId:role:sha256hex");
            }
            // The client id is "who" in the audit trail and a break's default assignee: same charset as both.
            if (!CLIENT_ID.matcher(parts[0]).matches()) {
                throw new IllegalStateException("API key client id '" + parts[0] + "' must match " + CLIENT_ID);
            }
            byte[] hash = HexFormat.of().parseHex(parts[2]);
            if (hash.length != 32) {
                throw new IllegalStateException("API key hash for client " + parts[0] + " is not SHA-256");
            }
            if (result.stream().anyMatch(e -> e.caller().clientId().equals(parts[0]))) {
                throw new IllegalStateException("API key client " + parts[0] + " is listed twice");
            }
            result.add(new Entry(new ApiCaller(parts[0], Role.parse(parts[1])), hash));
        }
        return result;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Entry(ApiCaller caller, byte[] hash) {
    }
}
