package dev.zenith.pearlplus;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.zenith.pearlplus.PearlPlusConfig.AutoPearl;

/** Preserve explicit grants/revocations while moving an automatic base alias to a new chamber. */
public final class BasePearlPolicy {
    private BasePearlPolicy() {}

    public static boolean canAssign(UUID owner, String scope, String base, String target,
                                    List<String> permissions, Map<String, AutoPearl> registered) {
        return canAssign(owner, scope, base, target, permissions, registered, false);
    }

    public static boolean canAssign(UUID owner, String scope, String base, String target,
                                    List<String> permissions, Map<String, AutoPearl> registered, boolean explicitAccess) {
        var existing = registered.get(target);
        var parsed = permissions.stream().flatMap(value -> PearlEntry.parse(value).stream()).toList();
        if (existing != null) {
            if (!owner.equals(existing.owner()) || !scope.equals(existing.scope())) return false;
            if (base.equalsIgnoreCase(existing.alias())) return explicitAccess
                && parsed.stream().noneMatch(p -> p.alias().equalsIgnoreCase(base));
            // Legacy pearl1 naming may migrate only while its exact grant is still present.
            if (!explicitAccess && parsed.stream().noneMatch(p -> p.alias().equalsIgnoreCase(existing.alias()) && p.target().equals(target)))
                return false;
        }
        var current = parsed.stream().filter(p -> p.alias().equalsIgnoreCase(base)).findFirst();
        if (current.isPresent()) {
            if (current.get().target().equals(target)) return true;
            var previous = registered.get(current.get().target());
            // Never replace a manually assigned base alias or one belonging to another location/owner.
            return previous != null && owner.equals(previous.owner()) && scope.equals(previous.scope())
                && base.equalsIgnoreCase(previous.alias());
        }
        // A previously registered base with no remaining permission was revoked.
        return explicitAccess || registered.values().stream().noneMatch(p -> owner.equals(p.owner())
            && scope.equals(p.scope()) && base.equalsIgnoreCase(p.alias()));
    }
}
