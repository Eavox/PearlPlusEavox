package dev.zenith.pearlplus;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.zenith.pearlplus.PearlPlusConfig.AutoPearl;

/** Pin each base to its first assignment while respecting explicit revocations. */
public final class BasePearlPolicy {
    private BasePearlPolicy() {}

    public static boolean canAssign(UUID owner, String scope, String base, String target,
                                    List<String> permissions, Map<String, AutoPearl> registered) {
        return canAssign(owner, scope, base, target, permissions, registered, false);
    }

    public static boolean canAssign(UUID owner, String scope, String base, String target,
                                    List<String> permissions, Map<String, AutoPearl> registered, boolean explicitAccess) {
        var parsed = permissions.stream().flatMap(value -> PearlEntry.parse(value).stream()).toList();
        // An assigned base stays pinned, including manual assignments and after restarts.
        if (parsed.stream().anyMatch(p -> p.alias().equalsIgnoreCase(base))) return false;
        var existing = registered.get(target);
        if (existing != null) {
            if (!owner.equals(existing.owner()) || !scope.equals(existing.scope())) return false;
            if (base.equalsIgnoreCase(existing.alias())) return explicitAccess;
            return explicitAccess || parsed.stream().anyMatch(p ->
                p.alias().equalsIgnoreCase(existing.alias()) && p.target().equals(target));
        }
        // A saved original spot survives revocation/regrant; a new pearl cannot replace it.
        return registered.values().stream().noneMatch(p -> owner.equals(p.owner())
            && scope.equals(p.scope()) && base.equalsIgnoreCase(p.alias()));
    }
}
