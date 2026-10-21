package dev.zenith.pearlplus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** UUID-bound, single-use offers. All times use the monotonic clock. */
public final class RefillOffers {
    public record Offer(String name, String alias, Object session, long created) {}
    public record Reply(Offer offer, boolean yes) {}
    private final Map<UUID, Offer> pending = new HashMap<>();
    public synchronized boolean add(UUID player, String name, String alias, Object session, long now) {
        pending.entrySet().removeIf(e -> expired(e.getValue(), now));
        if (pending.size() >= 64 && !pending.containsKey(player)) return false;
        pending.put(player, new Offer(name, alias, session, now));
        return true;
    }
    public synchronized Reply reply(UUID player, String message, Object session, long now) {
        if (message == null) return null;
        String answer = message.trim();
        if (!answer.equalsIgnoreCase("yes") && !answer.equalsIgnoreCase("no")) return null;
        Offer offer = pending.remove(player);
        if (offer == null || expired(offer, now) || offer.session() != session) return null;
        return new Reply(offer, answer.equalsIgnoreCase("yes"));
    }
    public synchronized void cancel(UUID player, String base) {
        var offer = pending.get(player);
        if (offer != null && offer.alias().equalsIgnoreCase(base)) pending.remove(player);
    }
    public synchronized void clear() { pending.clear(); }
    private boolean expired(Offer offer, long now) {
        return now - offer.created() >= TimeUnit.SECONDS.toNanos(60);
    }
}
