package dev.zenith.pearlplus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChamberResetTest {
    private final UUID player = UUID.randomUUID(), other = UUID.randomUUID(), oldPearl = UUID.randomUUID();
    private PearlPlusConfig configured() {
        var c = new PearlPlusConfig();
        c.autoLoad.allowBase(player,"Alex","crew");
        c.autoLoad.allow(player,"Alex",new PearlEntry("crew","old"));
        c.autoDetect.remember("old",new PearlPlusConfig.AutoPearl(player,"crew","scope",1,2,3));
        c.autoLoad.allowBase(other,"Other","crew");
        c.autoDetect.remember("other",new PearlPlusConfig.AutoPearl(other,"crew","scope",4,5,6));
        c.autoLoad.allowBase(player,"Alex","home");
        c.autoDetect.remember("home",new PearlPlusConfig.AutoPearl(player,"home","scope",7,8,9));
        return c;
    }
    @Test void denyThenAllowCanAssignNewChamber() {
        var c=configured();
        assertTrue(c.clearChamber(player,"CREW",false,Set.of(oldPearl)));
        assertFalse(c.autoLoad.canAutoRegister(player,"crew"));
        assertNull(c.autoDetect.get("old"));
        c.autoLoad.allowBase(player,"Alex","crew");
        assertTrue(BasePearlPolicy.canAssign(player,"scope","crew","new",c.autoLoad.entries(player),c.autoDetect.snapshot(),true));
        assertTrue(c.autoDetect.ignored(player,"crew",oldPearl));
        assertFalse(c.autoDetect.ignored(player,"crew",UUID.randomUUID()));
    }
    @Test void resetKeepsAccessAndIsolatesOtherPlayersAndBases() {
        var c=configured(); c.clearChamber(player,"crew",true,Set.of(oldPearl));
        assertTrue(c.autoLoad.hasExplicitBaseAccess(player,"crew"));
        assertTrue(c.autoLoad.entries(player).isEmpty());
        assertNotNull(c.autoDetect.get("other")); assertNotNull(c.autoDetect.get("home"));
        assertTrue(c.autoLoad.canAutoRegister(other,"crew")); assertTrue(c.autoLoad.canAutoRegister(player,"home"));
        assertFalse(c.autoDetect.ignored(other,"crew",oldPearl));
        assertFalse(c.autoDetect.ignored(player,"home",oldPearl));
    }
    @Test void resetCannotGrantAccessToAnUnapprovedPlayer() {
        var c=new PearlPlusConfig(); c.clearChamber(player,"crew",true,Set.of());
        assertFalse(c.autoLoad.canAutoRegister(player,"crew"));
    }
    @Test void resetStateSurvivesRestart() {
        var c=configured(); c.clearChamber(player,"crew",true,Set.of(oldPearl));
        var gson=new com.google.gson.Gson();
        var loaded=gson.fromJson(gson.toJson(c),PearlPlusConfig.class);
        assertNull(loaded.autoDetect.get("old"));
        assertTrue(loaded.autoDetect.ignored(player,"crew",oldPearl));
        assertTrue(loaded.autoLoad.canAutoRegister(player,"crew"));
    }
}
