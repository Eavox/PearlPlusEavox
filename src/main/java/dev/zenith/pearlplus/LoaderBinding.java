package dev.zenith.pearlplus;
import java.util.*;
import dev.zenith.pearlplus.AutoPearlDetector.Position;
import dev.zenith.pearlplus.PearlPlusConfig.AutoPearl;
public final class LoaderBinding {
    public record Loader(String id, Position position) {}
    public record Result(String target, boolean create, String error) {}
    /** One immutable index per scan, with a fresh index for the single committing candidate. */
    public static final class Index {
        private final Map<String,List<Loader>> byId = new HashMap<>();
        private final Map<Position,List<Loader>> byPosition = new HashMap<>();
        private final Map<String,AutoPearl> saved;
        private final Map<String,List<AutoPearl>> savedPositions = new HashMap<>();
        private final Map<String,Set<UUID>> grantees = new HashMap<>();
        public Index(List<Loader> loaders, Map<String,AutoPearl> saved, Map<UUID,List<String>> grants) {
            this.saved = Map.copyOf(saved);
            for (var l:loaders) { byId.computeIfAbsent(l.id(),k->new ArrayList<>()).add(l); byPosition.computeIfAbsent(l.position(),k->new ArrayList<>()).add(l); }
            for(var s:saved.values()) savedPositions.computeIfAbsent(key(s.scope(),new Position(s.x(),s.y(),s.z())),k->new ArrayList<>()).add(s);
            grants.forEach((owner,entries)->entries.forEach(raw->PearlEntry.parse(raw).ifPresent(entry->grantees.computeIfAbsent(entry.target(),k->new HashSet<>()).add(owner))));
        }
        private String key(String scope, Position pos) { return scope + "/" + pos; }
        public Result resolve(String desired, UUID owner, String scope, Position pos) {
            for(var l:byId.getOrDefault(desired,List.of())) if(!l.position().equals(pos)) return error("automatic loader ID exists at a different position");
            var matches=byPosition.getOrDefault(pos,List.of());
            if(matches.size()>1) return error("multiple Zenith loaders occupy this spot");
            String target=matches.isEmpty()?desired:matches.getFirst().id();
            if(!matches.isEmpty()&&!target.startsWith("auto-")) return error("manual Zenith loader '"+target+"' occupies this spot");
            var known=saved.get(target);
            if(known!=null) {
                if(!owner.equals(known.owner())||!scope.equals(known.scope())) return error("this automatic loader belongs to another player or server");
                if(known.x()!=pos.x()||known.y()!=pos.y()||known.z()!=pos.z()) return error("saved loader coordinates changed; reset this chamber explicitly");
            }
            for(var record:savedPositions.getOrDefault(key(scope,pos),List.of()))
                if(!owner.equals(record.owner())) return error("this automatic loader belongs to another player");
            if(grantees.getOrDefault(target,Set.of()).stream().anyMatch(id->!owner.equals(id))) return error("another player has access to this loader");
            return new Result(target,matches.isEmpty(),null);
        }
        private Result error(String reason) { return new Result(null,false,reason); }
    }
    public static Result resolve(String desired, UUID owner, String scope, Position pos,
                                 List<Loader> loaders, Map<String,AutoPearl> saved, Map<UUID,List<String>> grants) {
        return new Index(loaders,saved,grants).resolve(desired,owner,scope,pos);
    }
}
