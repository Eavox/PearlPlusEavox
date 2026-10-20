package dev.zenith.pearlplus;

import java.util.Optional;

/** Retains the existing alias=target JSON representation. */
public record PearlEntry(String alias, String target) {
    public static boolean validToken(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
            && value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c) || c == '=');
    }

    public static Optional<PearlEntry> parse(String value) {
        if (value == null) return Optional.empty();
        String[] parts = value.trim().split("=", 2);
        String alias = parts[0].trim();
        String target = parts.length == 2 ? parts[1].trim() : alias;
        if (target.isEmpty()) target = alias;
        if (!validToken(alias) || !validToken(target)) return Optional.empty();
        return Optional.of(new PearlEntry(alias, target));
    }

    public String serialize() {
        // IDs in Zenith are case-sensitive, even though aliases are not.
        return alias.equals(target) ? alias : alias + "=" + target;
    }
}
