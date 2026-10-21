package dev.zenith.pearlplus;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
public final class ChatOutbox {
    public static final int CAPACITY = 32;
    public record Message(Object session, String recipient, String text, Runnable onDispatch, long queuedAt,
                          BooleanSupplier valid, int priority) {}
    private final List<Message> pending = new ArrayList<>();
    private long lastSent;
    private boolean sent;
    private long discarded, rejected;
    public synchronized boolean add(Object session, String recipient, String text, Runnable callback, long now) {
        return add(session, recipient, text, callback, now, () -> true);
    }
    public synchronized boolean add(Object session, String recipient, String text, Runnable callback, long now, BooleanSupplier valid) {
        return add(session, recipient, text, callback, now, valid, 1);
    }
    public synchronized boolean add(Object session, String recipient, String text, Runnable callback, long now, BooleanSupplier valid, int priority) {
        prune(session, now);
        if (pending.size() >= CAPACITY) {
            int lowest = 0;
            for (int i=1;i<pending.size();i++) if (pending.get(i).priority() < pending.get(lowest).priority()) lowest=i;
            if (pending.get(lowest).priority() >= priority) { rejected++; return false; }
            pending.remove(lowest); discarded++;
        }
        pending.add(new Message(session, recipient, text, callback, now, valid, priority));
        return true;
    }
    private void prune(Object session, long now) {
        var it = pending.iterator();
        while (it.hasNext()) {
            var m = it.next();
            if (m.session() != session || now - m.queuedAt() >= TimeUnit.SECONDS.toNanos(120) || !m.valid().getAsBoolean()) {
                it.remove(); discarded++;
            }
        }
    }
    public synchronized Message poll(Object session, long now) {
        prune(session, now);
        if (pending.isEmpty() || (sent && now-lastSent < TimeUnit.SECONDS.toNanos(3))) return null;
        int best=0;
        for (int i=1;i<pending.size();i++) if (pending.get(i).priority()>pending.get(best).priority()) best=i;
        sent=true; lastSent=now;
        return pending.remove(best);
    }
    public synchronized String status() { return "Queued: " + pending.size() + "; discarded/expired: " + discarded + "; rejected: " + rejected; }
    // Same-session cancellation must not bypass the server chat cooldown.
    public synchronized void clear() { discarded += pending.size(); pending.clear(); }
}
