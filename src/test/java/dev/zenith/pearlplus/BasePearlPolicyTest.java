package dev.zenith.pearlplus;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BasePearlPolicyTest {
    @Test void updatingAnAssignmentPreservesIndependentBaseAccess() {
        var config = new PearlPlusConfig().autoLoad;
        config.allowBase(eavox, "Eavox", "crewbase");
        config.allow(eavox, "Eavox", new PearlEntry("crewbase", "old"));
        assertTrue(config.removeAssignment(eavox, "crewbase"));
        assertTrue(config.hasExplicitBaseAccess(eavox, "crewbase"));
        assertTrue(config.canAutoRegister(eavox, "crewbase"));
        config.allow(eavox, "Eavox", new PearlEntry("crewbase", "new"));
        assertEquals(List.of("crewbase=new"), config.entries(eavox));
        assertTrue(config.deny(eavox, "crewbase"));
        assertFalse(config.canAutoRegister(eavox, "crewbase"));
    }

    @Test void pendingBaseGrantReachesDetectorWithoutZenithWhitelist() {
        var config = new PearlPlusConfig().autoLoad;
        var detector = new AutoPearlDetector();
        var pearl = new AutoPearlDetector.Pearl(UUID.randomUUID(), 7, 2.5, 10.3, 3.5);
        var owners = Map.of(7, new AutoPearlDetector.Owner(eavox, "Eavox"));
        AutoPearlDetector.Blocks chamber = (x,y,z) -> y == 10 ? "water" : y == 11 ? "oak_trapdoor" : "air";
        detector.scan(List.of(pearl), owners, id -> config.canAutoRegister(id, "crewbase"), chamber, 0);
        config.allowBase(eavox, "Eavox", "crewbase");
        var detected = detector.scan(List.of(pearl), owners, id -> config.canAutoRegister(id, "crewbase"),
            chamber, java.util.concurrent.TimeUnit.SECONDS.toNanos(6));
        assertEquals(1, detected.size());
        assertEquals(eavox, detected.getFirst().owner().uuid());
        config.deny(eavox, "crewbase");
        assertTrue(detector.scan(List.of(pearl), owners, id -> config.canAutoRegister(id, "crewbase"),
            chamber, java.util.concurrent.TimeUnit.SECONDS.toNanos(7)).isEmpty());
    }

    @Test void pluginAccessCanBeGrantedBeforeAnyPearlExists() {
        var config = new PearlPlusConfig();
        config.autoLoad.allowBase(eavox, "Eavox", "crewbase");
        assertTrue(config.autoLoad.entries(eavox).isEmpty());
        assertTrue(config.autoLoad.canAutoRegister(eavox, "CREWBASE"));
        assertFalse(config.autoLoad.canAutoRegister(eavox, "anotherbase"));
        assertFalse(config.autoLoad.canAutoRegister(friend, "crewbase"));
        assertEquals(List.of("crewbase"), config.autoLoad.accessSnapshot().get(eavox));
    }

    @Test void noPluginEntryMeansNoAutoAccess() {
        assertFalse(new PearlPlusConfig().autoLoad.canAutoRegister(eavox, "crewbase"));
    }

    @Test void denyRevokesBothPendingAndAssignedPluginAccess() {
        var config = new PearlPlusConfig();
        config.autoLoad.allowBase(eavox, "Eavox", "crewbase");
        config.autoLoad.allow(eavox, "Eavox", new PearlEntry("crewbase", "target"));
        assertTrue(config.autoLoad.deny(eavox, "CREWBASE"));
        assertFalse(config.autoLoad.canAutoRegister(eavox, "crewbase"));
        assertTrue(config.autoLoad.entries(eavox).isEmpty());
        assertTrue(config.autoLoad.accessSnapshot().isEmpty());
    }

    @Test void pendingGrantCanBeRevokedBeforePearlIsDetected() {
        var config = new PearlPlusConfig();
        config.autoLoad.allowBase(eavox, "Eavox", "crewbase");
        assertTrue(config.autoLoad.deny(eavox, "crewbase"));
        assertFalse(config.autoLoad.canAutoRegister(eavox, "crewbase"));
    }

    @Test void explicitPluginRegrantCanRestoreKnownChamber() {
        assertTrue(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "old",
            List.of(), Map.of("old", record(eavox, "crewbase")), true));
    }

    @Test void explicitGrantDoesNotMakeOldChambersReplaceCurrentAssignmentRepeatedly() {
        assertFalse(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "old",
            List.of("crewbase=new"), Map.of("old", record(eavox, "crewbase")), true));
    }

    private final UUID eavox = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final String scope = "server/overworld";
    private PearlPlusConfig.AutoPearl record(UUID owner, String alias) {
        return new PearlPlusConfig.AutoPearl(owner, alias, scope, 1, 2, 3);
    }

    @Test void twoPlayersCanUseSameBaseNameForDifferentPearls() {
        var config = new PearlPlusConfig();
        assertTrue(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "eavox-target", List.of(), Map.of()));
        assertTrue(BasePearlPolicy.canAssign(friend, scope, "crewbase", "friend-target", List.of(), Map.of()));
        config.autoLoad.allow(eavox, "Eavox", new PearlEntry("crewbase", "eavox-target"));
        config.autoLoad.allow(friend, "Friend", new PearlEntry("crewbase", "friend-target"));
        var publicChat = LoadInput.parse("load Crewbase", true).orElseThrow();
        var whisper = LoadInput.parse("load crewbase", false).orElseThrow();
        assertEquals("eavox-target", publicChat.select(config.autoLoad.entries(eavox)).orElseThrow().target());
        assertEquals("friend-target", whisper.select(config.autoLoad.entries(friend)).orElseThrow().target());
    }

    @Test void manuallyAssignedBaseIsNotOverwritten() {
        assertFalse(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "new",
            List.of("crewbase=manual"), Map.of()));
    }

    @Test void newChamberCanReplaceSamePlayersOldAutomaticBaseTarget() {
        assertTrue(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "new",
            List.of("crewbase=old"), Map.of("old", record(eavox, "crewbase"))));
    }

    @Test void repeatedObservationDoesNotRestoreRevokedKeyword() {
        assertFalse(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "old",
            List.of(), Map.of("old", record(eavox, "crewbase"))));
    }

    @Test void newChamberCannotBypassBaseRevocation() {
        assertFalse(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "new",
            List.of(), Map.of("old", record(eavox, "crewbase"))));
    }

    @Test void activeLegacyGeneratedNameCanMigrate() {
        assertTrue(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "old",
            List.of("pearl1=old"), Map.of("old", record(eavox, "pearl1"))));
    }

    @Test void revokedLegacyGeneratedNameCannotMigrate() {
        assertFalse(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "old",
            List.of(), Map.of("old", record(eavox, "pearl1"))));
    }

    @Test void unrelatedOwnersAndScopesCannotReplaceExistingTarget() {
        assertFalse(BasePearlPolicy.canAssign(eavox, scope, "crewbase", "new",
            List.of("crewbase=old"), Map.of("old", record(friend, "crewbase"))));
        assertFalse(BasePearlPolicy.canAssign(eavox, "other/overworld", "crewbase", "new",
            List.of("crewbase=old"), Map.of("old", record(eavox, "crewbase"))));
    }
}
