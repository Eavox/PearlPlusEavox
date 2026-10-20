package dev.zenith.pearlplus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class AutoPearlNaming {
    private AutoPearlNaming() {}

    public static String target(String scope, UUID owner, AutoPearlDetector.Position pos) {
        String key = scope + "|" + owner + "|" + pos.x() + "|" + pos.y() + "|" + pos.z();
        return "auto-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static String availableAlias(List<String> existing) {
        for (int i = 1; i <= AutoPearlDetector.MAX_TRACKED; i++) {
            String alias = "pearl" + i;
            if (existing.stream().flatMap(v -> PearlEntry.parse(v).stream())
                .noneMatch(p -> p.alias().equalsIgnoreCase(alias))) return alias;
        }
        return null;
    }
}
