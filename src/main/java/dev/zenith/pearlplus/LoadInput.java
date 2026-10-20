package dev.zenith.pearlplus;

import java.util.List;
import java.util.Optional;

public record LoadInput(String alias) {
    public static Optional<LoadInput> parse(String message, boolean allowNoise) {
        if (message == null) return Optional.empty();
        String[] words = message.trim().split("\\s+", 3);
        if (!words[0].equalsIgnoreCase("load") || (!allowNoise && words.length > 2))
            return Optional.empty();
        return Optional.of(new LoadInput(words.length > 1 ? words[1] : null));
    }

    public Optional<PearlEntry> select(List<String> entries) {
        return entries.stream().flatMap(value -> PearlEntry.parse(value).stream())
            .filter(entry -> alias == null || entry.alias().equalsIgnoreCase(alias))
            .findFirst();
    }
}
