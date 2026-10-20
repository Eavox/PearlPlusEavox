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
        ticks = 0;
        scope = null;
        scanStatus = "Waiting for a fresh scan";
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
            detector.clear();
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
                    return;
                }
            }
        }
        var candidates = detector.scan(pearls, owners, id -> PLUGIN_CONFIG.autoLoad.canAutoRegister(id, AutoPearlNaming.baseAlias(PLUGIN_CONFIG.autoDetect.baseName)),
            (x, y, z) -> World.isChunkLoadedBlockPos(x, z) ? World.getBlock(x, y, z).name() : null,
            System.nanoTime());
        var eligible = candidates.stream().filter(this::eligible).toList();
        String base = AutoPearlNaming.baseAlias(PLUGIN_CONFIG.autoDetect.baseName);
        long permitted = owners.values().stream().filter(o -> PLUGIN_CONFIG.autoLoad.canAutoRegister(o.uuid(), base)).count();
        scanStatus = "Visible pearls: " + pearls.size() + "; visible players with base access: " + permitted
            + "; settled candidates: " + candidates.size() + "; eligible assignments: " + eligible.size();
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
        String target = AutoPearlNaming.target(scope, owner.uuid(), pos);
        var existing = PLUGIN_CONFIG.autoDetect.get(target);
        if (!enabledSetting()) return "automatic detection is disabled";
        if (!PLUGIN_CONFIG.autoLoad.canAutoRegister(owner.uuid(), AutoPearlNaming.baseAlias(PLUGIN_CONFIG.autoDetect.baseName)))
            return "base permission was removed";
        if (existing == null && PLUGIN_CONFIG.autoDetect.size() >= AutoPearlDetector.MAX_TRACKED)
            return "saved chamber limit reached (256)";
        return binding(candidate).error();
    }

    private LoaderBinding.Result binding(AutoPearlDetector.Candidate candidate) {
        var pos = candidate.trigger();
        return LoaderBinding.resolve(AutoPearlNaming.target(scope, candidate.owner().uuid(), pos),
            candidate.owner().uuid(), scope, pos,
            CONFIG.client.extra.pearlLoader.pearls.stream().map(p -> new LoaderBinding.Loader(p.id(),
                new AutoPearlDetector.Position(p.x(), p.y(), p.z()))).toList(),
            PLUGIN_CONFIG.autoDetect.snapshot(), PLUGIN_CONFIG.autoLoad.snapshot());
    }

    private boolean eligible(AutoPearlDetector.Candidate candidate) {
        var config = PLUGIN_CONFIG;
        String base = AutoPearlNaming.baseAlias(config.autoDetect.baseName);
        if (base == null || !config.autoLoad.canAutoRegister(candidate.owner().uuid(), base)) return false;
        var resolved = binding(candidate);
        String target = resolved.error() == null ? resolved.target()
            : AutoPearlNaming.target(scope, candidate.owner().uuid(), candidate.trigger());
        return BasePearlPolicy.canAssign(candidate.owner().uuid(), scope, base, target,
            config.autoLoad.entries(candidate.owner().uuid()), config.autoDetect.snapshot(),
            config.autoLoad.hasExplicitBaseAccess(candidate.owner().uuid(), base));
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
                MODULE.get(AutoLoadModule.class).whisper(owner.name(),
                    "Pearl saved as " + alias + ". Say load " + alias + " in chat or whisper it to me.");
            } catch (Exception e) {
                warn("Pearl registered, but setup whisper could not be sent: {}", e.getMessage());
            }
        }
        return true;
    }
}
