package dev.zenith.pearlplus;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PearlPlusTest {
    @Test void legacyEntryStillWorks() {
        assertEquals(new PearlEntry("Spawn", "Spawn"), PearlEntry.parse(" Spawn ").orElseThrow());
        assertEquals(new PearlEntry("Spawn", "Spawn"), PearlEntry.parse("Spawn=").orElseThrow());
    }

    @Test void targetCaseSurvivesRoundTrip() {
        var entry = new PearlEntry("spawn", "Spawn");
        assertEquals("spawn=Spawn", entry.serialize());
        assertEquals(entry, PearlEntry.parse(entry.serialize()).orElseThrow());
    }

    @Test void malformedEntriesCannotBecomeCommands() {
        for (String input : Arrays.asList(null, "", " ", "=Spawn", "alias=two words", "alias=a=b", "a\nb", "a\u0000b"))
            assertTrue(PearlEntry.parse(input).isEmpty(), String.valueOf(input));
    }

    @Test void commandBoundaryIsExact() {
        for (String message : List.of("loader", "loading Spawn", "!load Spawn", ""))
            assertTrue(LoadInput.parse(message, true).isEmpty());
        assertTrue(LoadInput.parse("  LoAd   SpAwN  ", false).isPresent());
    }

    @Test void strictModeRejectsNoise() {
        assertTrue(LoadInput.parse("load Spawn random", false).isEmpty());
        var input = LoadInput.parse("load Spawn random trailing noise", true).orElseThrow();
        assertEquals("Spawn", input.alias());
    }

    @Test void permissionsAreNeverBypassedByNoise() {
        var input = LoadInput.parse("load Forbidden random", true).orElseThrow();
        assertTrue(input.select(List.of("Spawn=PlayerOne")).isEmpty());
    }

    @Test void defaultSelectionSkipsMalformedEntries() {
        var input = LoadInput.parse("load", false).orElseThrow();
        assertEquals("PlayerOne", input.select(Arrays.asList(null, "", "Spawn=PlayerOne")).orElseThrow().target());
    }

    @Test void aliasesIgnoreCaseButTargetsDoNot() {
        var input = LoadInput.parse("load sPaWn", false).orElseThrow();
        assertEquals("PlayerOne", input.select(List.of("Spawn=PlayerOne")).orElseThrow().target());
    }

    @Test void updatedPermissionPreservesOrderAndExactTarget() {
        var config = new PearlPlusConfig.AutoLoadConfig();
        var uuid = UUID.randomUUID();
        config.allow(uuid, "Player", new PearlEntry("Spawn", "Old"));
        config.allow(uuid, "Player", new PearlEntry("Base", "Other"));
        config.allow(uuid, "Player", new PearlEntry("spawn", "Spawn"));
        assertEquals(List.of("spawn=Spawn", "Base=Other"), config.entries(uuid));
    }

    @Test void revocationByUuidNeedsNoProfileService() {
        var config = new PearlPlusConfig.AutoLoadConfig();
        var uuid = UUID.randomUUID();
        config.allow(uuid, "Player", new PearlEntry("Spawn", "Player"));
        assertTrue(config.deny(uuid, "SPAWN"));
        assertTrue(config.snapshot().isEmpty());
        assertFalse(config.deny(uuid, "Spawn"));
    }

    @Test void snapshotsDoNotShareMutableLists() {
        var config = new PearlPlusConfig.AutoLoadConfig();
        var uuid = UUID.randomUUID();
        config.allow(uuid, "Player", new PearlEntry("Spawn", "Player"));
        config.snapshot().get(uuid).clear();
        config.entries(uuid).clear();
        assertEquals(1, config.entries(uuid).size());
    }

    @Test void missingNameUsesUuidWithoutNetwork() {
        var uuid = UUID.randomUUID();
        assertEquals(uuid.toString(), new PearlPlusConfig.AutoLoadConfig().displayName(uuid));
    }

    @Test void oneRequestAtATime() {
        var tracker = new RequestTracker();
        long token = tracker.begin(100, 3);
        assertNotEquals(0, token);
        assertEquals(0, tracker.begin(200, 3));
        tracker.release(token);
        assertNotEquals(0, tracker.begin(200, 3));
    }

    @Test void completionDoesNotReleaseAnnouncementSlotAndCannotRepeat() {
        var tracker = new RequestTracker();
        long token = tracker.begin(100, 3);
        assertTrue(tracker.complete(token));
        assertFalse(tracker.complete(token));
        assertEquals(0, tracker.begin(200, 3));
    }

    @Test void cancellationInvalidatesOldCallbacksEvenAfterRestart() {
        var tracker = new RequestTracker();
        long old = tracker.begin(100, 3);
        tracker.cancel();
        long current = tracker.begin(200, 3);
        assertFalse(tracker.complete(old));
        tracker.release(old);
        assertTrue(tracker.isCurrent(current));
        assertTrue(tracker.complete(current));
    }

    @Test void cooldownSurvivesImmediateFailureOrCancellation() {
        var tracker = new RequestTracker();
        tracker.begin(100, 3);
        tracker.cancel();
        assertEquals(0, tracker.begin(102, 3));
        assertNotEquals(0, tracker.begin(103, 3));
    }
}
