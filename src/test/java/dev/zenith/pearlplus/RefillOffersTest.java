package dev.zenith.pearlplus;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RefillOffersTest {
    private final RefillOffers offers = new RefillOffers();
    private final UUID player = UUID.randomUUID();
    private final Object session = new Object();
    private void add() { assertTrue(offers.add(player, "Alex", "crewbase", session, 0)); }
    @Test void yesIsCaseInsensitiveAndSingleUse() {
        add();
        var reply = offers.reply(player, " YES ", session, 1);
        assertTrue(reply.yes());
        assertEquals("crewbase", reply.offer().alias());
        assertNull(offers.reply(player, "yes", session, 2));
    }
    @Test void noConsumesTheOfferWithoutDropping() {
        add();
        assertFalse(offers.reply(player, "no", session, 1).yes());
        assertNull(offers.reply(player, "yes", session, 2));
    }
    @Test void AnotherPlayerCannotAccept() {
        add();
        assertNull(offers.reply(UUID.randomUUID(), "yes", session, 1));
        assertNotNull(offers.reply(player, "yes", session, 2));
    }
    @Test void otherMessagesDoNotConsumeOffer() {
        add();
        for (String message : new String[]{null, "load crewbase", "yes please", ""})
            assertNull(offers.reply(player, message, session, 1));
        assertNotNull(offers.reply(player, "yes", session, 2));
    }
    @Test void expiresAtSixtySeconds() {
        add();
        assertNull(offers.reply(player, "yes", session, TimeUnit.SECONDS.toNanos(60)));
    }
    @Test void cannotAcceptFromPreviousConnection() {
        add();
        assertNull(offers.reply(player, "yes", new Object(), 1));
    }
    @Test void cancellationInvalidatesOffers() {
        add(); offers.clear();
        assertNull(offers.reply(player, "yes", session, 1));
    }
    @Test void storageIsBoundedAndExpiredOffersAreReclaimed() {
        for (int i = 0; i < 64; i++) assertTrue(offers.add(UUID.randomUUID(), "Alex", "base", session, 0));
        assertFalse(offers.add(player, "Alex", "base", session, 1));
        assertTrue(offers.add(player, "Alex", "base", session, TimeUnit.SECONDS.toNanos(60)));
    }
}
