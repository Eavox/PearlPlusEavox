package dev.zenith.pearlplus;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RefillConfigTest {
    @Test void lowStockOnlyAsksDecliningPlayersExceptWhenEmpty() {
        for (int stock=1;stock<5;stock++) {
            assertTrue(PearlPlusConfig.RefillConfig.shouldAsk(stock, true));
            assertFalse(PearlPlusConfig.RefillConfig.shouldAsk(stock, false));
        }
        assertTrue(PearlPlusConfig.RefillConfig.shouldAsk(0, true));
        assertTrue(PearlPlusConfig.RefillConfig.shouldAsk(0, false));
        assertFalse(PearlPlusConfig.RefillConfig.shouldAsk(5, true));
        assertFalse(PearlPlusConfig.RefillConfig.shouldAsk(16, false));
        assertFalse(PearlPlusConfig.RefillConfig.shouldAsk(-1, true));
    }
    @Test void requestHistoryIsPerPlayerAndOnlyExplicitResetClearsIt() {
        var config = new PearlPlusConfig.RefillConfig();
        var player = UUID.randomUUID(); var other = UUID.randomUUID();
        config.markAsked(player); config.markAsked(player);
        assertEquals(1,config.count()); assertTrue(config.hasAsked(player)); assertFalse(config.hasAsked(other));
        config.enabled=false; config.enabled=true;
        assertTrue(config.hasAsked(player));
        config.reset(); assertFalse(config.hasAsked(player)); assertEquals(0,config.count());
    }
    @Test void historySurvivesJsonSaveAndReload() {
        var config = new PearlPlusConfig.RefillConfig(); var player = UUID.randomUUID();
        config.markAsked(player); config.enabled=false;
        var gson = new com.google.gson.Gson();
        var restored = gson.fromJson(gson.toJson(config),PearlPlusConfig.RefillConfig.class);
        assertTrue(restored.hasAsked(player)); assertFalse(restored.enabled);
    }
    @Test void cancellingRefillInvalidatesQueuedMessageWithoutClearingPublicAnnouncement() {
        var queue = new ChatOutbox(); var session = new Object();
        var generation = new java.util.concurrent.atomic.AtomicLong(); long token = generation.get();
        queue.add(session,"Player","refill",()->{},0,()->generation.get()==token);
        queue.add(session,null,"public announcement",()->{},0);
        generation.incrementAndGet();
        var announcement=queue.poll(session,0);
        assertTrue(announcement.valid().getAsBoolean()); assertNull(announcement.recipient());
    }
}
