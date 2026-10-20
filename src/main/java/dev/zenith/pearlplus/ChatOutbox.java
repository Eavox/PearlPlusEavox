package dev.zenith.pearlplus;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** Bounded FIFO with a shared cooldown for public chat and whispers. */
public final class ChatOutbox {
    public record Message(Object session, String recipient, String text, Runnable onDispatch, long queuedAt) {}
    private final ArrayDeque<Message> pending = new ArrayDeque<>();
    private long lastSent;
    private boolean sent;
    public synchronized boolean add(Object session, String recipient, String text, Runnable onDispatch, long now) {
        if (pending.size() >= 64) return false;
        pending.add(new Message(session, recipient, text, onDispatch, now));
        return true;
    }
    public synchronized Message poll(Object session, long now) {
        while (!pending.isEmpty() && (pending.peek().session() != session
            || now - pending.peek().queuedAt() >= TimeUnit.SECONDS.toNanos(120))) pending.remove();
        if (pending.isEmpty() || (sent && now - lastSent < TimeUnit.SECONDS.toNanos(3))) return null;
        sent = true; lastSent = now;
        return pending.remove();
    }
    public synchronized void clear() { pending.clear(); sent = false; }
}
