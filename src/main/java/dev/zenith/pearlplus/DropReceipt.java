package dev.zenith.pearlplus;
/** Observes server-originated inventory updates, never optimistic local inventory edits. */
public final class DropReceipt {
    private int slot = -1, before;
    private boolean decreased;
    public synchronized void expect(int slot, int before) { this.slot=slot; this.before=before; decreased=false; }
    public synchronized void update(int slot, int remaining) {
        if(this.slot==slot && before>0) decreased = remaining==before-1;
    }
    public synchronized boolean decreased() { return decreased; }
}
