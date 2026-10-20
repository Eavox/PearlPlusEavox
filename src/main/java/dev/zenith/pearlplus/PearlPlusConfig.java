package dev.zenith.pearlplus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PearlPlusConfig {
    public final AutoLoadConfig autoLoad = new AutoLoadConfig();
    public static class AutoLoadConfig {
        public volatile boolean enabled = true;
        public volatile boolean allowNoiseAfterPearl = false;
        public volatile boolean publicChatEnabled = false;
        public volatile int publicChatDelaySeconds = 7;
        public volatile String publicChatSuccessMessage = "Pearl interaction completed for {player}";
        public Map<UUID, List<String>> allowed = new LinkedHashMap<>();
        public Map<UUID, String> playerNames = new LinkedHashMap<>();

        public synchronized List<String> entries(UUID uuid) {
            if (allowed == null) allowed = new LinkedHashMap<>();
            var entries = allowed.get(uuid);
            return entries == null ? List.of() : new ArrayList<>(entries);
        }

        public synchronized Map<UUID, List<String>> snapshot() {
            Map<UUID, List<String>> copy = new LinkedHashMap<>();
            if (allowed != null) allowed.forEach((uuid, entries) -> {
                if (uuid != null) copy.put(uuid, entries == null ? List.of() : new ArrayList<>(entries));
            });
            return copy;
        }

        public synchronized void allow(UUID uuid, String name, PearlEntry pearl) {
            if (allowed == null) allowed = new LinkedHashMap<>();
            var entries = allowed.computeIfAbsent(uuid, ignored -> new ArrayList<>());
            for (int i = 0; i < entries.size(); i++) {
                if (PearlEntry.parse(entries.get(i)).map(p -> p.alias().equalsIgnoreCase(pearl.alias())).orElse(false)) {
                    entries.set(i, pearl.serialize());
                    rememberName(uuid, name);
                    return;
                }
            }
            entries.add(pearl.serialize());
            rememberName(uuid, name);
        }

        public synchronized boolean deny(UUID uuid, String alias) {
            if (allowed == null) return false;
            var entries = allowed.get(uuid);
            if (entries == null) return false;
            boolean removed = entries.removeIf(value -> PearlEntry.parse(value)
                .map(p -> p.alias().equalsIgnoreCase(alias)).orElse(false));
            if (entries.isEmpty()) {
                allowed.remove(uuid);
                if (playerNames != null) playerNames.remove(uuid);
            }
            return removed;
        }

        public synchronized void rememberName(UUID uuid, String name) {
            if (playerNames == null) playerNames = new LinkedHashMap<>();
            playerNames.put(uuid, name);
        }

        public synchronized String displayName(UUID uuid) {
            return playerNames == null ? uuid.toString() : playerNames.getOrDefault(uuid, uuid.toString());
        }
    }

    public final AutoDetectConfig autoDetect = new AutoDetectConfig();
    public static final class AutoDetectConfig { }
}
