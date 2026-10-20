package dev.zenith.pearlplus;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static dev.zenith.pearlplus.AutoPearlDetector.*;

class AutoPearlDetectorTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID pearlId = UUID.randomUUID();
    private final Pearl pearl = new Pearl(pearlId, 7, 2.5, 10.3, 3.5);
    private final Map<Integer, Owner> owners = Map.of(7, new Owner(owner, "Alex"));
    private final Blocks chamber = (x, y, z) -> {
        if (x != 2 || z != 3) return "air";
        if (y == 9 || y == 10) return "minecraft:bubble_column";
        if (y == 11 || y == 12) return "minecraft:oak_trapdoor";
        return "minecraft:air";
    };
    private long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }

    @Test void choosesLowerOfTwoTrapdoors() {
        assertEquals(new Position(2, 11, 3), findTrigger(pearl, chamber).orElseThrow());
    }

    @Test void flyingPearlWithoutWaterIsNotAChamber() {
        assertTrue(findTrigger(pearl, (x, y, z) -> y == 11 ? "oak_trapdoor" : "air").isEmpty());
    }

    @Test void obstructionPreventsChoosingHigherTrapdoor() {
        assertTrue(findTrigger(pearl, (x, y, z) -> y == 11 ? "stone" : chamber.name(x, y, z)).isEmpty());
    }

    @Test void ironLowerTrapdoorDoesNotFallThroughToUpperWoodTrapdoor() {
        assertTrue(findTrigger(pearl, (x, y, z) -> y == 11 ? "iron_trapdoor" : chamber.name(x, y, z)).isEmpty());
    }

    @Test void requiresFiveSecondsOfSettling() {
        var detector = new AutoPearlDetector();
        assertTrue(detector.scan(List.of(pearl), owners, id -> true, chamber, 0).isEmpty());
        assertTrue(detector.scan(List.of(pearl), owners, id -> true, chamber, seconds(4)).isEmpty());
        var result = detector.scan(List.of(pearl), owners, id -> true, chamber, seconds(5));
        assertEquals(1, result.size());
        assertEquals(owner, result.getFirst().owner().uuid());
        assertEquals(new Position(2, 11, 3), result.getFirst().trigger());
    }

    @Test void nonWhitelistedOwnerNeverRegisters() {
        var detector = new AutoPearlDetector();
        detector.scan(List.of(pearl), owners, id -> false, chamber, 0);
        assertTrue(detector.scan(List.of(pearl), owners, id -> false, chamber, seconds(10)).isEmpty());
    }

    @Test void unknownOwnerIsNotInferredLaterFromReusedEntityId() {
        var detector = new AutoPearlDetector();
        detector.scan(List.of(pearl), Map.of(), id -> true, chamber, 0);
        assertTrue(detector.scan(List.of(pearl), owners, id -> true, chamber, seconds(10)).isEmpty());
    }

    @Test void ownerBindingSurvivesOwnerLeavingRenderRange() {
        var detector = new AutoPearlDetector();
        detector.scan(List.of(pearl), owners, id -> true, chamber, 0);
        assertEquals(owner, detector.scan(List.of(pearl), Map.of(), id -> true, chamber, seconds(5))
            .getFirst().owner().uuid());
    }

    @Test void sharedTriggerWithDifferentOwnersIsRejected() {
        var detector = new AutoPearlDetector();
        var other = new Pearl(UUID.randomUUID(), 8, 2.6, 10.4, 3.5);
        var map = new HashMap<>(owners);
        map.put(8, new Owner(UUID.randomUUID(), "Other"));
        detector.scan(List.of(pearl, other), map, id -> true, chamber, 0);
        assertTrue(detector.scan(List.of(pearl, other), map, id -> true, chamber, seconds(10)).isEmpty());
    }

    @Test void unknownPearlSharingTriggerAlsoBlocksRegistration() {
        var detector = new AutoPearlDetector();
        var other = new Pearl(UUID.randomUUID(), 0, 2.6, 10.4, 3.5);
        detector.scan(List.of(pearl, other), owners, id -> true, chamber, 0);
        assertTrue(detector.scan(List.of(pearl, other), owners, id -> true, chamber, seconds(10)).isEmpty());
    }

    @Test void brokenChamberResetsSettleTimer() {
        var detector = new AutoPearlDetector();
        detector.scan(List.of(pearl), owners, id -> true, chamber, 0);
        detector.scan(List.of(pearl), owners, id -> true, (x, y, z) -> "air", seconds(4));
        assertTrue(detector.scan(List.of(pearl), owners, id -> true, chamber, seconds(6)).isEmpty());
        assertEquals(1, detector.scan(List.of(pearl), owners, id -> true, chamber, seconds(11)).size());
    }

    @Test void disconnectClearRequiresFreshObservation() {
        var detector = new AutoPearlDetector();
        detector.scan(List.of(pearl), owners, id -> true, chamber, 0);
        detector.clear();
        assertTrue(detector.scan(List.of(pearl), owners, id -> true, chamber, seconds(10)).isEmpty());
    }

    @Test void trackingIsBounded() {
        var detector = new AutoPearlDetector();
        var flood = new ArrayList<Pearl>();
        for (int i = 0; i <= MAX_TRACKED; i++)
            flood.add(new Pearl(UUID.randomUUID(), 7, 2.5, 10.3, 3.5));
        assertTrue(detector.scan(flood, owners, id -> true, chamber, seconds(10)).isEmpty());
    }

    @Test void autoTargetsAreStableButScopedToOwnerServerDimensionAndPosition() {
        Position pos = new Position(2, 11, 3);
        String id = AutoPearlNaming.target("server/overworld", owner, pos);
        assertEquals(id, AutoPearlNaming.target("server/overworld", owner, pos));
        assertNotEquals(id, AutoPearlNaming.target("server/nether", owner, pos));
        assertNotEquals(id, AutoPearlNaming.target("other/overworld", owner, pos));
        assertNotEquals(id, AutoPearlNaming.target("server/overworld", UUID.randomUUID(), pos));
        assertNotEquals(id, AutoPearlNaming.target("server/overworld", owner, new Position(2, 12, 3)));
        assertTrue(PearlEntry.validToken(id));
    }

    @Test void baseNameIsTheAutomaticKeyword() {
        assertEquals("crewbase", AutoPearlNaming.baseAlias("Crewbase"));
        assertNull(AutoPearlNaming.baseAlias(""));
        assertNull(AutoPearlNaming.baseAlias(null));
        assertNull(AutoPearlNaming.baseAlias("Crew Base"));
        assertNull(AutoPearlNaming.baseAlias("Crew=Base"));
    }

    @Test void revokingPermissionDoesNotEraseRememberedRegistration() {
        var config = new PearlPlusConfig();
        config.autoLoad.allow(owner, "Alex", new PearlEntry("pearl1", "auto-target"));
        config.autoDetect.remember("auto-target", new PearlPlusConfig.AutoPearl(owner, "pearl1", "scope", 2, 11, 3));
        assertTrue(config.autoLoad.deny(owner, "pearl1"));
        assertNotNull(config.autoDetect.get("auto-target"));
        assertTrue(config.autoLoad.entries(owner).isEmpty());
    }

    @Test void automaticLoadRequiresCorrectOwnerWhitelistAndScope() {
        var registered = new PearlPlusConfig.AutoPearl(owner, "pearl1", "scope", 2, 11, 3);
        assertTrue(AutoPearlAccess.mayLoad(registered, owner, "scope", true));
        assertFalse(AutoPearlAccess.mayLoad(registered, owner, "scope", false));
        assertFalse(AutoPearlAccess.mayLoad(registered, UUID.randomUUID(), "scope", true));
        assertFalse(AutoPearlAccess.mayLoad(registered, owner, "other-dimension", true));
        assertTrue(AutoPearlAccess.mayLoad(null, owner, "scope", false)); // manual grants still supported
    }
}
