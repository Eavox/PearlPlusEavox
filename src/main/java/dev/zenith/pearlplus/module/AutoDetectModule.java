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
    }

    public static String currentScope() {
        return CONFIG.client.server.address + ":" + CONFIG.client.server.port
            + "/" + World.getCurrentDimension().name();
    }

    private synchronized void scanTick() {
        if (!enabledSetting() || !Proxy.getInstance().isConnected() || Proxy.getInstance().isInQueue()) return;
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
                    .map(p -> p.getName()).orElse(null);
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
        var candidates = detector.scan(pearls, owners, id -> PLAYER_LISTS.getWhitelist().contains(id),
            (x, y, z) -> World.isChunkLoadedBlockPos(x, z) ? World.getBlock(x, y, z).name() : null,
            System.nanoTime());
        for (var candidate : candidates) {
            if (MODULE.get(AutoLoadModule.class).registerWhenIdle(() -> register(candidate))) {
                saveConfigAsync();
                break; // at most one new chamber/save/whisper per second
            }
        }
    }

    private boolean register(AutoPearlDetector.Candidate candidate) {
        var config = PLUGIN_CONFIG;
        var owner = candidate.owner();
        var pos = candidate.trigger();
        String target = AutoPearlNaming.target(scope, owner.uuid(), pos);
        if (!enabledSetting() || !PLAYER_LISTS.getWhitelist().contains(owner.uuid())
            || config.autoDetect.get(target) != null || config.autoDetect.size() >= AutoPearlDetector.MAX_TRACKED)
            return false;
        // Do not overwrite or share an existing loader at this trigger.
        if (CONFIG.client.extra.pearlLoader.pearls.stream().anyMatch(p ->
            p.id().equals(target) || (p.x() == pos.x() && p.y() == pos.y() && p.z() == pos.z()))) return false;
        synchronized (config.autoLoad) {
            String alias = AutoPearlNaming.availableAlias(config.autoLoad.entries(owner.uuid()));
            if (alias == null) return false;
            CONFIG.client.extra.pearlLoader.pearls.add(new Pearl(target, pos.x(), pos.y(), pos.z()));
            config.autoLoad.allow(owner.uuid(), owner.name(), new PearlEntry(alias, target));
            config.autoDetect.remember(target, new AutoPearl(owner.uuid(), alias, scope, pos.x(), pos.y(), pos.z()));
            info("Automatically registered {} for {}", alias, owner.name());
            try {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(owner.name(),
                    "Pearl saved as " + alias + ". Say load " + alias + " in chat or whisper it to me."));
            } catch (Exception e) {
                warn("Pearl registered, but setup whisper could not be sent: {}", e.getMessage());
            }
        }
        return true;
    }
}
