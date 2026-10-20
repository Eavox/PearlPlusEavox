package dev.zenith.pearlplus;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import dev.zenith.pearlplus.AutoPearlDetector.*;
import dev.zenith.pearlplus.LoaderBinding.*;

class LoaderBindingTest {
    private final UUID owner = UUID.randomUUID();
    private final Position spot = new Position(-636723, 69, -880006);
    private final String scope = "server/overworld";
    private Result resolve(List<Loader> loaders, Map<String, PearlPlusConfig.AutoPearl> saved, Map<UUID,List<String>> grants) {
        return LoaderBinding.resolve("auto-new", owner, scope, spot, loaders, saved, grants);
    }
    @Test void orphanAutomaticLoaderAtDetectedSpotIsReused() {
        var result = resolve(List.of(new Loader("auto-old", spot)), Map.of(), Map.of());
        assertNull(result.error()); assertEquals("auto-old", result.target()); assertFalse(result.create());
    }
    @Test void sameDeterministicIdSurvivingConfigLossIsReused() {
        var result = resolve(List.of(new Loader("auto-new", spot)), Map.of(), Map.of());
        assertNull(result.error()); assertFalse(result.create());
    }
    @Test void existingAssignmentRemainsPinnedAfterAdditionalPearlAndRestart() {
        var config = new PearlPlusConfig().autoLoad;
        config.allowBase(owner, "Eavox", "crew");
        var result = resolve(List.of(new Loader("auto-old", spot)), Map.of(), Map.of());
        assertTrue(BasePearlPolicy.canAssign(owner, scope, "crew", result.target(), config.entries(owner), Map.of(), true));
        config.allow(owner, "Eavox", new PearlEntry("crew", result.target()));
        var saved = Map.of(result.target(), new PearlPlusConfig.AutoPearl(owner, "crew", scope, spot.x(), spot.y(), spot.z()));
        assertFalse(BasePearlPolicy.canAssign(owner, scope, "crew", "auto-other", config.entries(owner), saved, true));
        assertFalse(BasePearlPolicy.canAssign(owner, scope, "crew", result.target(), config.entries(owner), saved, true));
        // Even when the active grant was removed, regrant preserves the remembered original spot.
        assertFalse(BasePearlPolicy.canAssign(owner, scope, "crew", "auto-other", List.of(), saved, true));
        assertTrue(BasePearlPolicy.canAssign(owner, scope, "crew", result.target(), List.of(), saved, true));
    }
    @Test void cannotClaimAnotherPlayersSavedSpot() {
        var saved = Map.of("auto-old", new PearlPlusConfig.AutoPearl(UUID.randomUUID(), "crew", scope, spot.x(), spot.y(), spot.z()));
        assertNotNull(resolve(List.of(new Loader("auto-old", spot)), saved, Map.of()).error());
    }
    @Test void cannotClaimLoaderGrantedToAnotherPlayer() {
        assertNotNull(resolve(List.of(new Loader("auto-old", spot)), Map.of(), Map.of(UUID.randomUUID(), List.of("crew=auto-old"))).error());
    }
    @Test void manualAndDuplicateLoadersAreNotSilentlyAdopted() {
        assertNotNull(resolve(List.of(new Loader("manual", spot)), Map.of(), Map.of()).error());
        assertNotNull(resolve(List.of(new Loader("auto-a", spot), new Loader("auto-b", spot)), Map.of(), Map.of()).error());
    }
    @Test void movedIdIsNotRedirectedAndEmptySpotCanBeCreated() {
        assertNotNull(resolve(List.of(new Loader("auto-new", new Position(1,2,3))), Map.of(), Map.of()).error());
        var result = resolve(List.of(), Map.of(), Map.of());
        assertTrue(result.create()); assertNull(result.error());
    }
    @Test void candidatesKeepFirstObservedOrderEvenWhenInputOrderChanges() {
        var detector = new AutoPearlDetector();
        var first = new Pearl(UUID.randomUUID(), 7, 2.5, 10.3, 3.5);
        var later = new Pearl(UUID.randomUUID(), 7, 9.5, 10.3, 3.5);
        var owners = Map.of(7, new Owner(owner, "Eavox"));
        Blocks chamber = (x,y,z) -> y == 10 ? "water" : y == 11 ? "oak_trapdoor" : "air";
        detector.scan(List.of(first), owners, id -> true, chamber, 0);
        detector.scan(List.of(later, first), owners, id -> true, chamber, 1_000_000_000L);
        var candidates = detector.scan(List.of(later, first), owners, id -> true, chamber, 7_000_000_000L);
        assertEquals(2, candidates.size());
        assertEquals(first.uuid(), candidates.getFirst().pearlUuid());
    }
}
