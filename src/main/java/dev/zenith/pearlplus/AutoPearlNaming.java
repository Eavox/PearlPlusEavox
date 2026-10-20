package dev.zenith.pearlplus;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public final class AutoPearlNaming {
    private AutoPearlNaming() {}

    public static String target(String scope, UUID owner, AutoPearlDetector.Position pos) {
        String key = scope + "|" + owner + "|" + pos.x() + "|" + pos.y() + "|" + pos.z();
        return "auto-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static String baseAlias(String baseName) {
        if (!PearlEntry.validToken(baseName)) return null;
        return baseName.toLowerCase(Locale.ROOT);
    }
}
