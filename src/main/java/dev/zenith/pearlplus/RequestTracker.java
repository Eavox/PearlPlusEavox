package dev.zenith.pearlplus;

/** Access under the module's state lock. Tokens invalidate late callbacks. */
public final class RequestTracker {
    private long sequence;
    private long active;
    private long lastAccepted;
    private boolean acceptedBefore;
    private boolean completed;

    public long begin(long now, long cooldown) {
        if (active != 0 || (acceptedBefore && now - lastAccepted < cooldown)) return 0;
        active = ++sequence;
        lastAccepted = now;
        acceptedBefore = true;
        completed = false;
        return active;
    }

    public boolean isCurrent(long token) {
        return token != 0 && active == token;
    }

    public boolean isBusy() {
        return active != 0;
    }

    public boolean complete(long token) {
        if (!isCurrent(token) || completed) return false;
        completed = true;
        return true;
    }

    public void release(long token) {
        if (isCurrent(token)) active = 0;
    }

    public void cancel() {
        active = 0;
    }
}
