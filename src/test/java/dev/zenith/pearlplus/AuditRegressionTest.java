package dev.zenith.pearlplus;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import dev.zenith.pearlplus.AutoPearlDetector.*;

class AuditRegressionTest {
    private final UUID a=UUID.randomUUID(), b=UUID.randomUUID();
    private final Object session=new Object();
    private final Blocks blocks=(x,y,z)->y==10?"water":y==11?"oak_trapdoor":"air";
    private Pearl pearl(int owner,double x) { return new Pearl(UUID.randomUUID(),owner,x,10.3,3.5); }
    private Map<Integer,Owner> owners() { return Map.of(7,new Owner(a,"Alex"),8,new Owner(b,"Bob")); }

    @Test void freshPearlIsSelectableWhenIgnoredOldPearlSharesTheChamber() {
        var d=new AutoPearlDetector(); var old=pearl(7,2.5); var fresh=pearl(7,2.5);
        var c=new PearlPlusConfig(); c.autoLoad.allowBase(a,"Alex","crew");
        c.clearChamber(a,"crew",true,Set.of(old.uuid()));
        d.scan(List.of(old,fresh),owners(),id->true,blocks,0);
        var found=d.scan(List.of(old,fresh),owners(),id->true,blocks,6_000_000_000L,
            (owner,id)->!c.autoDetect.ignored(owner,"crew",id));
        assertEquals(1,found.size()); assertEquals(fresh.uuid(),found.getFirst().pearlUuid());
    }
    @Test void ignoredUnknownOwnerStillBlocksUnsafeSharedChamber() {
        var d=new AutoPearlDetector(); var unknown=pearl(0,2.5); var fresh=pearl(7,2.5);
        d.scan(List.of(unknown,fresh),owners(),id->true,blocks,0);
        assertTrue(d.scan(List.of(unknown,fresh),owners(),id->true,blocks,6_000_000_000L,
            (owner,id)->!id.equals(unknown.uuid())).isEmpty());
    }
    @Test void ignoredOtherOwnerStillBlocksSharedChamber() {
        var d=new AutoPearlDetector(); var old=pearl(8,2.5); var fresh=pearl(7,2.5);
        d.scan(List.of(old,fresh),owners(),id->true,blocks,0);
        assertTrue(d.scan(List.of(old,fresh),owners(),id->true,blocks,6_000_000_000L,
            (owner,id)->!id.equals(old.uuid())).isEmpty());
    }
    @Test void playerResetPreservesOtherPlayerAndOtherBaseMessagesOffersAndOwnership() {
        var scope=new InteractionScope(); var out=new ChatOutbox(); var offers=new RefillOffers();
        var cancelled=scope.token(a,"crew"); var home=scope.token(a,"home"); var bob=scope.token(b,"crew");
        out.add(session,"Alex","cancel",()->{},0,cancelled);
        out.add(session,"Bob","pull complete",()->{},0,bob);
        out.add(session,"Alex","home",()->{},0,home);
        offers.add(a,"Alex","crew",session,0); offers.add(b,"Bob","crew",session,0);
        var d=new AutoPearlDetector(); var pearl=pearl(8,9.5);
        d.scan(List.of(pearl),owners(),id->true,blocks,0);
        scope.cancel(a,"CREW"); offers.cancel(a,"crew");
        assertFalse(cancelled.getAsBoolean()); assertTrue(home.getAsBoolean()); assertTrue(bob.getAsBoolean());
        assertEquals("pull complete",out.poll(session,0).text());
        assertNotNull(offers.reply(b,"yes",session,1)); assertNull(offers.reply(a,"yes",session,1));
        assertEquals(b,d.scan(List.of(pearl),Map.of(),id->true,blocks,6_000_000_000L).getFirst().owner().uuid());
        assertTrue(scope.token(a,"crew").getAsBoolean()); assertFalse(cancelled.getAsBoolean());
    }
    @Test void sameSessionCancellationDoesNotResetChatCooldown() {
        var out=new ChatOutbox(); out.add(session,null,"first",()->{},0); out.poll(session,0);
        out.clear(); out.add(session,null,"second",()->{},1);
        assertNull(out.poll(session,1)); assertNotNull(out.poll(session,3_000_000_000L));
    }
    @Test void cancelledMessagesArePrunedBeforePriorityOrCooldown() {
        var out=new ChatOutbox();
        out.add(session,"Alex","cancelled",()->{},0,()->false,2);
        out.add(session,null,"live",()->{},0);
        assertEquals("live",out.poll(session,0).text());
    }
    @Test void confirmationDisplacesDonationAtCapacity() {
        var out=new ChatOutbox();
        for(int i=0;i<ChatOutbox.CAPACITY;i++) assertTrue(out.add(session,"Alex","donation",()->{},0,()->true,0));
        assertTrue(out.add(session,null,"confirmed",()->{},0,()->true,2));
        assertEquals("confirmed",out.poll(session,0).text());
        assertTrue(out.status().contains("discarded/expired: 1"));
    }
    @Test void acceptedFullQueueCanDrainBeforeExpiryWithoutNewTraffic() {
        var out=new ChatOutbox();
        for(int i=0;i<ChatOutbox.CAPACITY;i++) out.add(session,null,"m"+i,()->{},0);
        for(int i=0;i<ChatOutbox.CAPACITY;i++) assertEquals("m"+i,out.poll(session,i*3_000_000_000L).text());
    }
    @Test void packetEvidenceCanResolvePreviouslyUnknownOwnershipWithoutGuessing() {
        var d=new AutoPearlDetector(); var pearl=pearl(7,2.5);
        d.scan(List.of(pearl),Map.of(),id->true,blocks,0);
        assertTrue(d.scan(List.of(pearl),owners(),id->true,blocks,6_000_000_000L).isEmpty());
        d.captureOwner(pearl.uuid(),new Owner(a,"Alex"));
        assertEquals(a,d.scan(List.of(pearl),Map.of(),id->true,blocks,7_000_000_000L).getFirst().owner().uuid());
    }
    @Test void ownerCannotBeOverwrittenByReusedEntityIdOrConflictingEvidence() {
        var d=new AutoPearlDetector(); var pearl=pearl(7,2.5);
        d.captureOwner(pearl.uuid(),new Owner(a,"Alex"));
        d.scan(List.of(pearl),Map.of(),id->true,blocks,0);
        d.captureOwner(pearl.uuid(),new Owner(b,"Bob"));
        assertEquals(a,d.scan(List.of(pearl),Map.of(7,new Owner(b,"Bob")),id->true,blocks,6_000_000_000L).getFirst().owner().uuid());
        d.forget(pearl.uuid());
        assertTrue(d.scan(List.of(pearl),Map.of(),id->true,blocks,7_000_000_000L).isEmpty());
    }
    @Test void capturedUuidCanRefreshItsNameButNotItsOwner() {
        var d=new AutoPearlDetector(); var pearl=pearl(7,2.5);
        d.captureOwner(pearl.uuid(),new Owner(a,a.toString()));
        d.scan(List.of(pearl),Map.of(),id->true,blocks,0);
        assertEquals("Alex",d.scan(List.of(pearl),owners(),id->true,blocks,6_000_000_000L).getFirst().owner().name());
        assertEquals("Alex",d.scan(List.of(pearl),Map.of(7,new Owner(b,"Bob")),id->true,blocks,7_000_000_000L).getFirst().owner().name());
    }
    @Test void knownLoaderCannotMoveThroughLegacyRecovery() {
        var pos=new Position(2,11,3);
        var saved=Map.of("auto-old",new PearlPlusConfig.AutoPearl(a,"crew","scope",90,91,92));
        var result=LoaderBinding.resolve("auto-new",a,"scope",pos,List.of(new LoaderBinding.Loader("auto-old",pos)),saved,Map.of());
        assertNotNull(result.error()); assertTrue(result.error().contains("coordinates"));
    }
    @Test void immutableIndexIsRebuiltToRevalidateConcurrentPermissionChange() {
        var pos=new Position(2,11,3); var loaders=List.of(new LoaderBinding.Loader("auto-old",pos));
        var grants=new HashMap<UUID,List<String>>();
        var before=new LoaderBinding.Index(loaders,Map.of(),grants);
        grants.put(b,List.of("crew=auto-old"));
        assertNull(before.resolve("auto-new",a,"scope",pos).error());
        assertNotNull(new LoaderBinding.Index(loaders,Map.of(),grants).resolve("auto-new",a,"scope",pos).error());
    }
    @Test void inventoryReceiptRequiresMatchingServerSlotDecrease() {
        var receipt=new DropReceipt(); receipt.expect(36,5);
        assertFalse(receipt.decreased()); receipt.update(37,4); assertFalse(receipt.decreased());
        receipt.update(36,5); assertFalse(receipt.decreased()); receipt.update(36,4); assertTrue(receipt.decreased());
        receipt.update(36,5); assertFalse(receipt.decreased());
    }
    @Test void moreThan256PearlsStillStopsDetectionAsRequested() {
        var d=new AutoPearlDetector(); var many=new ArrayList<Pearl>();
        for(int i=0;i<=AutoPearlDetector.MAX_TRACKED;i++) many.add(pearl(7,2.5));
        assertTrue(d.scan(many,owners(),id->true,blocks,6_000_000_000L).isEmpty());
        assertEquals(256,AutoPearlDetector.MAX_TRACKED);
    }
}
