package dev.zenith.pearlplus;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Bounded, once-per-second detection. No world mutations or block interactions. */
public final class AutoPearlDetector {
    public static final int MAX_TRACKED = 256;
    private static final long SETTLE_TIME = TimeUnit.SECONDS.toNanos(5);
    private final Map<UUID, Observation> observations = new HashMap<>();
    private long sequence;

    public record Position(int x, int y, int z) {}
    public record Owner(UUID uuid, String name) {}
    public record Pearl(UUID uuid, int ownerEntityId, double x, double y, double z) {}
    public record Candidate(UUID pearlUuid, Owner owner, Position trigger) {}
    public interface Blocks { String name(int x, int y, int z); }

    private static final class Observation {
        // Bind only on first sight. Never guess ownership from proximity or a reused entity ID.
        final Owner owner;
        final long order;
        Position trigger;
        long since;
        double x, y, z;
        Observation(Owner owner, long order) { this.owner = owner; this.order = order; }
    }

    public void clear() { observations.clear(); }

    public List<Candidate> scan(List<Pearl> pearls, Map<Integer, Owner> owners,
                                Predicate<UUID> whitelisted, Blocks blocks, long now) {
        if (pearls.size() > MAX_TRACKED) {
            clear();
            return List.of();
        }
        Set<UUID> visible = new HashSet<>();
        Map<Position, List<Pearl>> byTrigger = new HashMap<>();
        for (Pearl pearl : pearls) {
            if (pearl.uuid() == null) continue;
            visible.add(pearl.uuid());
            Observation observation = observations.computeIfAbsent(pearl.uuid(),
                ignored -> new Observation(pearl.ownerEntityId() > 0 ? owners.get(pearl.ownerEntityId()) : null, sequence++));
            Position trigger = findTrigger(pearl, blocks).orElse(null);
            if (trigger == null) {
                observation.trigger = null;
                continue;
            }
            byTrigger.computeIfAbsent(trigger, ignored -> new ArrayList<>()).add(pearl);
            if (!trigger.equals(observation.trigger)
                || Math.abs(pearl.x() - observation.x) > 0.6
                || Math.abs(pearl.z() - observation.z) > 0.6
                || Math.abs(pearl.y() - observation.y) > 1.5) {
                observation.trigger = trigger;
                observation.since = now;
                observation.x = pearl.x();
                observation.y = pearl.y();
                observation.z = pearl.z();
            }
        }
        observations.keySet().retainAll(visible);
        List<Candidate> ready = new ArrayList<>();
        for (var entry : byTrigger.entrySet()) {
            Set<UUID> distinctOwners = new HashSet<>();
            boolean unknownOwner = false;
            for (Pearl pearl : entry.getValue()) {
                Owner owner = observations.get(pearl.uuid()).owner;
                if (owner == null || owner.uuid() == null) unknownOwner = true;
                else distinctOwners.add(owner.uuid());
            }
            if (unknownOwner || distinctOwners.size() != 1) continue;
            for (Pearl pearl : entry.getValue()) {
                Observation observation = observations.get(pearl.uuid());
                if (whitelisted.test(observation.owner.uuid()) && now - observation.since >= SETTLE_TIME) {
                    ready.add(new Candidate(pearl.uuid(), observation.owner, entry.getKey()));
                    break;
                }
            }
        }
        ready.sort(Comparator.comparingLong(c -> observations.get(c.pearlUuid()).order));
        return ready;
    }

    public static Optional<Position> findTrigger(Pearl pearl, Blocks blocks) {
        if (!Double.isFinite(pearl.x()) || !Double.isFinite(pearl.y()) || !Double.isFinite(pearl.z()))
            return Optional.empty();
        int x = (int) Math.floor(pearl.x()), y = (int) Math.floor(pearl.y()), z = (int) Math.floor(pearl.z());
        boolean water = fluid(blocks.name(x, y, z)) || fluid(blocks.name(x, y - 1, z));
        if (!water) return Optional.empty();
        // Stop at the first trapdoor. In a double-trapdoor chamber, never choose the upper one.
        for (int blockY = y; blockY <= y + 2; blockY++) {
            String name = stripNamespace(blocks.name(x, blockY, z));
            if (name.endsWith("_trapdoor")) {
                if (name.equals("iron_trapdoor")) return Optional.empty(); // cannot right-click to toggle
                return Optional.of(new Position(x, blockY, z));
            }
            if (!name.equals("air") && !name.equals("cave_air") && !name.equals("void_air") && !fluid(name))
                return Optional.empty(); // solid/unknown block obscures the trigger
        }
        return Optional.empty();
    }

    private static boolean fluid(String name) {
        String normalized = stripNamespace(name);
        return normalized.equals("water") || normalized.equals("bubble_column");
    }

    private static String stripNamespace(String name) {
        if (name == null) return "unknown";
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }
}
