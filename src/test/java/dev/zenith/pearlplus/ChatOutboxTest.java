package dev.zenith.pearlplus;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatOutboxTest {
    private final ChatOutbox queue = new ChatOutbox();
    private final Object session = new Object();
    private void add(String recipient, String text, long now) {
        assertTrue(queue.add(session, recipient, text, () -> {}, now));
    }
    @Test void whisperAndPublicMessagesShareCooldownAndKeepOrder() {
        add("Eavox", "offer", 0); add(null, "announcement", 0); add("Eavox", "dropped", 0);
        assertEquals("offer", queue.poll(session, 0).text());
        assertNull(queue.poll(session, 2_999_999_999L));
        var publicMessage = queue.poll(session, 3_000_000_000L);
        assertNull(publicMessage.recipient()); assertEquals("announcement", publicMessage.text());
        assertEquals("dropped", queue.poll(session, 6_000_000_000L).text());
        assertNull(queue.poll(session, 9_000_000_000L));
    }
    @Test void cancelledAndPreviousSessionMessagesCannotLeakAfterReconnect() {
        add("Eavox", "old", 0);
        assertNull(queue.poll(new Object(), 1));
        add("Eavox", "cancelled", 2); queue.clear();
        assertNull(queue.poll(session, 3));
    }
    @Test void expiredMessagesAreDiscarded() {
        add(null, "old", 0);
        assertNull(queue.poll(session, 120_000_000_000L));
    }
    @Test void queueIsBounded() {
        for (int i=0;i<64;i++) add(null, "message", 0);
        assertFalse(queue.add(session, null, "overflow", () -> {}, 0));
    }
    @Test void offerTimeoutStartsWhenPromptIsDispatchedNotWhenQueued() {
        var offers = new RefillOffers();
        var player = java.util.UUID.randomUUID();
        queue.add(session, "Eavox", "offer", () -> offers.add(player, "Eavox", "crew", session, 30_000_000_000L), 0);
        assertNull(offers.reply(player, "yes", session, 1));
        queue.poll(session, 30_000_000_000L).onDispatch().run();
        assertNotNull(offers.reply(player, "yes", session, 80_000_000_000L));
    }
    @Test void publicCompletionAlwaysIncludesPlayerAndPrivateCompletionNeverDoes() {
        assertEquals("Eavox has been pulled !", PullAnnouncement.format(true, "Someone pulled", "Eavox", "crew", "12:00"));
        assertEquals("Member of Divinity have been pulled", PullAnnouncement.format(false, "{player} {pearl}", "Eavox", "crew", "12:00"));
    }
}
