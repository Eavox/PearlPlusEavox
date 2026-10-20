package dev.zenith.pearlplus;

import java.util.*;
import dev.zenith.pearlplus.AutoPearlDetector.Position;
import dev.zenith.pearlplus.PearlPlusConfig.AutoPearl;

/** Reuse an unclaimed automatic loader without changing its coordinates or another player's access. */
public final class LoaderBinding {
    public record Loader(String id, Position position) {}
    public record Result(String target, boolean create, String error) {}
    public static Result resolve(String desired, UUID owner, String scope, Position pos,
                                 List<Loader> loaders, Map<String, AutoPearl> saved,
                                 Map<UUID, List<String>> grants) {
        for (var loader : loaders) {
            if (loader.id().equals(desired) && !loader.position().equals(pos))
                return new Result(null, false, "automatic loader ID exists at a different position");
        }
        var matches = loaders.stream().filter(l -> l.position().equals(pos)).toList();
        if (matches.size() > 1) return new Result(null, false, "multiple Zenith loaders occupy this spot");
        String target = matches.isEmpty() ? desired : matches.getFirst().id();
        if (!matches.isEmpty() && !target.startsWith("auto-"))
            return new Result(null, false, "manual Zenith loader '" + target + "' occupies this spot");
        for (var entry : saved.entrySet()) {
            var record = entry.getValue();
            if (entry.getKey().equals(target) || (scope.equals(record.scope())
                && record.x() == pos.x() && record.y() == pos.y() && record.z() == pos.z())) {
                if (!owner.equals(record.owner()) || !scope.equals(record.scope()))
                    return new Result(null, false, "this automatic loader belongs to another player or server");
            }
        }
        for (var entry : grants.entrySet()) {
            if (!owner.equals(entry.getKey()) && entry.getValue().stream().flatMap(s -> PearlEntry.parse(s).stream())
                .anyMatch(p -> p.target().equals(target)))
                return new Result(null, false, "another player has access to this loader");
        }
        return new Result(target, matches.isEmpty(), null);
    }
}
