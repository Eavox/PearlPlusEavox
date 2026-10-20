package dev.zenith.pearlplus;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BasePearlPolicyTest {
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
