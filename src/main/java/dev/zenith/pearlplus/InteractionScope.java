package dev.zenith.pearlplus;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
public final class InteractionScope {
    public record Key(UUID player, String base) {
        public Key { base = base.toLowerCase(Locale.ROOT); }
    }
    private final Map<Key, AtomicBoolean> tokens = new HashMap<>();
    public synchronized BooleanSupplier token(UUID player, String base) {
        var flag = tokens.computeIfAbsent(new Key(player, base), k -> new AtomicBoolean(true));
        return flag::get;
    }
    public synchronized void cancel(UUID player, String base) {
        var flag = tokens.remove(new Key(player, base));
        if (flag != null) flag.set(false);
    }
    public synchronized void clear() { tokens.values().forEach(f -> f.set(false)); tokens.clear(); }
}
