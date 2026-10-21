package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientTickEvent;
import com.zenith.feature.player.World;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import com.zenith.util.config.Config.Client.Extra.PearlLoader.Pearl;
import dev.zenith.pearlplus.AutoPearlDetector;
import dev.zenith.pearlplus.AutoPearlNaming;
import dev.zenith.pearlplus.BasePearlPolicy;
import dev.zenith.pearlplus.LoaderBinding;
import dev.zenith.pearlplus.PearlEntry;
import dev.zenith.pearlplus.PearlPlusConfig.AutoPearl;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.*;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoDetectModule extends Module {
    private final AutoPearlDetector detector = new AutoPearlDetector();
    private int ticks;
    private String scope;
    private volatile String scanStatus = "No scan yet";
    private volatile String registrationStatus = "";
    private String lastRegistrationError = "";

    public String status() {
        return "Plugin: " + PLUGIN_CONFIG.autoLoad.enabled + "; autodetect: " + PLUGIN_CONFIG.autoDetect.enabled
            + "; base: " + PLUGIN_CONFIG.autoDetect.baseName + "\n" + scanStatus + "\n" + registrationStatus;
    }

    private final Map<Integer,UUID> observedEntities = new HashMap<>();
    @Override public com.zenith.network.codec.PacketHandlerCodec registerClientPacketHandlerCodec() {
        return com.zenith.network.codec.PacketHandlerCodec.clientBuilder().setId("pearlplus-ownership").setPriority(-5)
            .state(org.geysermc.mcprotocollib.protocol.data.ProtocolState.GAME,
                com.zenith.network.codec.PacketHandlerStateCodec.clientBuilder()
                    .inbound(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket.class,
                        new com.zenith.network.codec.ClientEventLoopPacketHandler<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket, com.zenith.network.client.ClientSession>() {
                            @Override public boolean applyAsync(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket packet, com.zenith.network.client.ClientSession session) {
                                if (session == Proxy.getInstance().getClient()) captureSpawn(packet); return true;
                            }
                        })
                    .inbound(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket.class,
                        new com.zenith.network.codec.ClientEventLoopPacketHandler<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket, com.zenith.network.client.ClientSession>() {
                            @Override public boolean applyAsync(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket packet, com.zenith.network.client.ClientSession session) {
                                if (session != Proxy.getInstance().getClient()) return true;
                                synchronized (AutoDetectModule.this) {
                                    for (int id:packet.getEntityIds()) { var uuid=observedEntities.remove(id); if(uuid!=null) detector.forget(uuid); }
                                }
                                return true;
                            }
                        }).build()).build();
    }
    private synchronized void captureSpawn(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket packet) {
        if(packet.getType()!=EntityType.ENDER_PEARL || !(packet.getData() instanceof ProjectileData data)) return;
        var prior=observedEntities.remove(packet.getEntityId()); if(prior!=null) detector.forget(prior);
        if(observedEntities.size()>=AutoPearlDetector.MAX_TRACKED) return;
        observedEntities.put(packet.getEntityId(),packet.getUuid());
        var owner=CACHE.getEntityCache().get(data.getOwnerId());
        if(owner instanceof EntityPlayer && owner.getUuid()!=null) {
            String name=CACHE.getTabListCache().get(owner.getUuid()).map(p->p.getName()).orElse(PLUGIN_CONFIG.autoLoad.displayName(owner.getUuid()));
            detector.captureOwner(packet.getUuid(),new AutoPearlDetector.Owner(owner.getUuid(),name));
        }
    }

    @Override public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoLoad.enabled && PLUGIN_CONFIG.autoDetect.enabled;
    }

    @Override public List<EventConsumer<?>> registerEvents() {
        return List.of(of(ClientTickEvent.class, event -> scanTick()),
            of(ClientDisconnectEvent.class, event -> reset()));
    }

    @Override public void onDisable() { reset(); }

    private synchronized void reset() {
        detector.clear();
        observedEntities.clear();
        ticks = 0;
        scope = null;
        scanStatus = "Waiting for a fresh scan";
    }

    public synchronized boolean clearChamber(UUID player, String base, boolean keepAccess) {
        MODULE.get(AutoLoadModule.class).cancelPlayer(player, base);
        var visible = new HashSet<UUID>();
        for (var entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.getEntityType() == EntityType.ENDER_PEARL && entity.getUuid() != null)
                visible.add(entity.getUuid());
        }
        boolean changed = PLUGIN_CONFIG.clearChamber(player, base, keepAccess, visible);
        // Keep ownership observations for unrelated pearls, including owners outside render range.
        scanStatus = "Chamber reset; waiting for a fresh scan";
        saveConfigAsync();
        return changed;
    }

    public synchronized void setBase(String base) {
        PLUGIN_CONFIG.autoDetect.baseName = base;
        reset();
    }

    public static String currentScope() {
        return CONFIG.client.server.address + ":" + CONFIG.client.server.port
            + "/" + World.getCurrentDimension().name();
    }

    private synchronized void scanTick() {
        if (!enabledSetting() || AutoPearlNaming.baseAlias(PLUGIN_CONFIG.autoDetect.baseName) == null
            || !Proxy.getInstance().isConnected() || Proxy.getInstance().isInQueue()) return;
        if (++ticks < 20) return;
        ticks = 0;
        String currentScope = currentScope();
        if (!Objects.equals(scope, currentScope)) {
            if (scope != null) { detector.clear(); observedEntities.clear(); }
            scope = currentScope;
        }
        var pearls = new ArrayList<AutoPearlDetector.Pearl>();
        var owners = new HashMap<Integer, AutoPearlDetector.Owner>();
        for (var entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity instanceof EntityPlayer && entity.getUuid() != null) {
                String name = CACHE.getTabListCache().get(entity.getUuid())
                    .map(p -> p.getName()).orElse(PLUGIN_CONFIG.autoLoad.displayName(entity.getUuid()));
                if (name != null && !name.isBlank())
                    owners.put(entity.getEntityId(), new AutoPearlDetector.Owner(entity.getUuid(), name));
            } else if (entity.getEntityType() == EntityType.ENDER_PEARL && !entity.isRemoved()) {
                int ownerId = entity.getObjectData() instanceof ProjectileData data ? data.getOwnerId() : 0;
                pearls.add(new AutoPearlDetector.Pearl(entity.getUuid(), ownerId,
                    entity.getX(), entity.getY(), entity.getZ()));
                if (pearls.size() > AutoPearlDetector.MAX_TRACKED) {
                    detector.clear();
                    scanStatus = "Detection paused: more than 256 visible pearls";
                    return;
                }
            }
        }
        String base = AutoPearlNaming.baseAlias(PLUGIN_CONFIG.autoDetect.baseName);
        var permissions = PLUGIN_CONFIG.autoLoad.snapshot();
        var grants = PLUGIN_CONFIG.autoLoad.accessSnapshot();
        var saved = PLUGIN_CONFIG.autoDetect.snapshot();
        var index = index(saved, permissions);
        var ignored = new HashMap<String, Set<UUID>>();
        synchronized (PLUGIN_CONFIG.autoDetect) {
            if (PLUGIN_CONFIG.autoDetect.ignoredPearls != null) PLUGIN_CONFIG.autoDetect.ignoredPearls.forEach((key,ids) -> {
                if (key.endsWith("/" + base)) ignored.put(key, Set.copyOf(ids));
            });
        }
        var allowed = new HashSet<UUID>();
        grants.forEach((id,bases) -> { if(bases.stream().anyMatch(b -> b.equalsIgnoreCase(base))) allowed.add(id); });
        permissions.forEach((id,entries) -> { if(entries.stream().flatMap(raw -> PearlEntry.parse(raw).stream())
            .anyMatch(entry -> entry.alias().equalsIgnoreCase(base))) allowed.add(id); });
        var candidates = detector.scan(pearls, owners, allowed::contains,
            (x, y, z) -> World.isChunkLoadedBlockPos(x, z) ? World.getBlock(x, y, z).name() : null,
            System.nanoTime(), (owner, pearl) -> !ignored.getOrDefault(owner + "/" + base, Set.of()).contains(pearl));
        var eligible = candidates.stream().filter(c -> eligible(c, base, permissions, grants, saved, index)).toList();
        long permitted = owners.values().stream().filter(o -> allowed.contains(o.uuid())).count();
        scanStatus = "Visible pearls: " + pearls.size() + "; visible players with base access: " + permitted
            + "; settled candidates: " + candidates.size() + "; eligible assignments: " + eligible.size()
            + "; unknown owners: " + detector.unknownOwners();
        var outcomes = new ArrayList<String>();
        var attempted = new HashSet<UUID>();
        for (var candidate : eligible) {
            if (!attempted.add(candidate.owner().uuid())) continue;
            String player = candidate.owner().name();
            String[] reason = {"waiting for the load module (busy or not started)"};
            try {
                if (MODULE.get(AutoLoadModule.class).registerWhenIdle(() -> {
                    reason[0] = registrationBlock(candidate);
                    return reason[0] == null && register(candidate);
                })) {
                    outcomes.add(player + ": registered successfully");
                    registrationStatus = String.join("\n", outcomes);
                    saveConfigAsync();
                    return; // at most one new chamber/save/whisper per second
                }
                outcomes.add(player + ": " + (reason[0] == null ? "assignment changed during registration; retrying" : reason[0]));
            } catch (Exception e) {
                String failure = player + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
                outcomes.add(failure);
                if (!failure.equals(lastRegistrationError)) {
                    error("Automatic pearl registration failed", e);
                    lastRegistrationError = failure;
                }
            }
        }
        registrationStatus = outcomes.isEmpty() ? "No pending eligible assignments" : String.join("\n", outcomes);
    }

    private String registrationBlock(AutoPearlDetector.Candidate candidate) {
        var owner = candidate.owner();
        var pos = candidate.trigger();
        if (!enabledSetting()) return "automatic detection is disabled";
        if (!PLUGIN_CONFIG.autoLoad.canAutoRegister(owner.uuid(), AutoPearlNaming.baseAlias(PLUGIN_CONFIG.autoDetect.baseName)))
            return "base permission was removed";
        var resolved = binding(candidate);
        if (resolved.error() != null) return resolved.error();
        if (PLUGIN_CONFIG.autoDetect.get(resolved.target()) == null && PLUGIN_CONFIG.autoDetect.size() >= AutoPearlDetector.MAX_TRACKED)
            return "saved chamber limit reached (256)";
        return null;
    }

    private LoaderBinding.Index index(Map<String, AutoPearl> saved, Map<UUID,List<String>> permissions) {
        return new LoaderBinding.Index(CONFIG.client.extra.pearlLoader.pearls.stream().map(p -> new LoaderBinding.Loader(p.id(),
            new AutoPearlDetector.Position(p.x(), p.y(), p.z()))).toList(), saved, permissions);
    }
    private LoaderBinding.Result binding(AutoPearlDetector.Candidate c) {
        return index(PLUGIN_CONFIG.autoDetect.snapshot(), PLUGIN_CONFIG.autoLoad.snapshot())
            .resolve(AutoPearlNaming.target(scope,c.owner().uuid(),c.trigger()),c.owner().uuid(),scope,c.trigger());
    }
    private boolean eligible(AutoPearlDetector.Candidate c, String base, Map<UUID,List<String>> permissions,
                             Map<UUID,List<String>> grants, Map<String,AutoPearl> saved, LoaderBinding.Index index) {
        var entries = permissions.getOrDefault(c.owner().uuid(),List.of());
        // Assigned players retain ownership observations, but need no loader lookup/allocation.
        if(entries.stream().flatMap(raw->PearlEntry.parse(raw).stream()).anyMatch(p->p.alias().equalsIgnoreCase(base))) return false;
        boolean explicit = grants.getOrDefault(c.owner().uuid(),List.of()).stream().anyMatch(b->b.equalsIgnoreCase(base));
        if(!explicit) return false;
        var resolved=index.resolve(AutoPearlNaming.target(scope,c.owner().uuid(),c.trigger()),c.owner().uuid(),scope,c.trigger());
        String target=resolved.error()==null?resolved.target():AutoPearlNaming.target(scope,c.owner().uuid(),c.trigger());
        return BasePearlPolicy.canAssign(c.owner().uuid(),scope,base,target,entries,saved,true);
    }
    private boolean eligible(AutoPearlDetector.Candidate c) {
        String base=AutoPearlNaming.baseAlias(PLUGIN_CONFIG.autoDetect.baseName);
        if(base==null || PLUGIN_CONFIG.autoDetect.ignored(c.owner().uuid(),base,c.pearlUuid())) return false;
        var permissions=PLUGIN_CONFIG.autoLoad.snapshot(); var saved=PLUGIN_CONFIG.autoDetect.snapshot();
        return eligible(c,base,permissions,PLUGIN_CONFIG.autoLoad.accessSnapshot(),saved,index(saved,permissions));
    }

    private boolean register(AutoPearlDetector.Candidate candidate) {
        var config = PLUGIN_CONFIG;
        var owner = candidate.owner();
        var pos = candidate.trigger();
        if (registrationBlock(candidate) != null) return false;
        synchronized (config.autoLoad) {
            String alias = AutoPearlNaming.baseAlias(config.autoDetect.baseName);
            if (alias == null || !eligible(candidate)) return false;
            var resolved = binding(candidate);
            if (resolved.error() != null) return false;
            String target = resolved.target();
            var existing = config.autoDetect.get(target);
            if (resolved.create()) CONFIG.client.extra.pearlLoader.pearls.add(new Pearl(target, pos.x(), pos.y(), pos.z()));
            if (existing != null) config.autoLoad.removeAssignment(owner.uuid(), existing.alias());
            config.autoLoad.allow(owner.uuid(), owner.name(), new PearlEntry(alias, target));
            config.autoDetect.remember(target, new AutoPearl(owner.uuid(), alias, scope, pos.x(), pos.y(), pos.z()));
            info("Automatically registered {} for {}", alias, owner.name());
            try {
                MODULE.get(AutoLoadModule.class).whisper(owner.uuid(), alias, owner.name(),
                    "Pearl saved as " + alias + ". Say load " + alias + " in chat or whisper it to me.");
            } catch (Exception e) {
                warn("Pearl registered, but setup whisper could not be sent: {}", e.getMessage());
            }
        }
        return true;
    }
}
