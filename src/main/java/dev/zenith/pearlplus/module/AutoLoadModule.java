package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.discord.Embed;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientTickEvent;
import java.util.UUID;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.LoadInput;
import dev.zenith.pearlplus.PullAnnouncement;
import dev.zenith.pearlplus.PearlEntry;
import dev.zenith.pearlplus.RequestTracker;
import dev.zenith.pearlplus.AutoPearlAccess;
import com.zenith.feature.player.World;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.COMMAND;
import static com.zenith.Globals.CONFIG;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoLoadModule extends Module {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(3);
    private final Object stateLock = new Object();
    private final RequestTracker requests = new RequestTracker();
    private final dev.zenith.pearlplus.InteractionScope scopes = new dev.zenith.pearlplus.InteractionScope();
    private UUID activePlayer;
    private String activeBase;
    private final OutgoingChat chat = new OutgoingChat();
    private final PearlRefill refill = new PearlRefill(chat, scopes);

    public void setRefill(boolean enabled) {
        synchronized (stateLock) { PLUGIN_CONFIG.refill.enabled = enabled; refill.clear(); }
    }
    public void resetDonationHistory() {
        synchronized (stateLock) { refill.clear(); PLUGIN_CONFIG.refill.reset(); }
    }
    public String stockStatus() {
        int count = refill.stock();
        return "Pearls: " + (count < 0 ? "unavailable (offline/queue)" : count)
            + "; refill: " + PLUGIN_CONFIG.refill.enabled + "; players already asked: " + PLUGIN_CONFIG.refill.count();
    }
    public String chatStatus() { return chat.status(); }
    public void whisper(UUID player, String base, String name, String message) {
        chat.enqueue(name, message, () -> {}, scopes.token(player, base));
    }
    public void cancelPlayer(UUID player, String base) {
        synchronized (stateLock) {
            scopes.cancel(player, base);
            refill.cancel(player, base);
            if (player.equals(activePlayer) && base.equalsIgnoreCase(activeBase)) cancelActiveRequest();
        }
    }
    private void cancelActiveRequest() {
        requests.cancel();
        activePlayer = null; activeBase = null;
        if (timeout != null) timeout.cancel(false);
        if (announcement != null) announcement.cancel(false);
        timeout = null; announcement = null;
    }
    private ScheduledThreadPoolExecutor scheduler;
    private ScheduledFuture<?> timeout;
    private ScheduledFuture<?> announcement;

    @Override public com.zenith.network.codec.PacketHandlerCodec registerClientPacketHandlerCodec() {
        return com.zenith.network.codec.PacketHandlerCodec.clientBuilder().setId("pearlplus-inventory-receipts").setPriority(-5)
            .state(org.geysermc.mcprotocollib.protocol.data.ProtocolState.GAME,
                com.zenith.network.codec.PacketHandlerStateCodec.clientBuilder()
                    .inbound(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket.class,
                        new com.zenith.network.codec.ClientEventLoopPacketHandler<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket, com.zenith.network.client.ClientSession>() {
                            @Override public boolean applyAsync(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket packet, com.zenith.network.client.ClientSession session) {
                                refill.serverSlot(packet.getContainerId(),packet.getSlot(),packet.getItem(),session); return true;
                            }
                        })
                    .inbound(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket.class,
                        new com.zenith.network.codec.ClientEventLoopPacketHandler<org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket, com.zenith.network.client.ClientSession>() {
                            @Override public boolean applyAsync(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket packet, com.zenith.network.client.ClientSession session) {
                                var items=packet.getItems();
                                for(int slot=0;slot<items.length;slot++) refill.serverSlot(packet.getContainerId(),slot,items[slot],session);
                                return true;
                            }
                        }).build()).build();
    }

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoLoad.enabled;
    }

    @Override
    public void onEnable() {
        synchronized (stateLock) {
            scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "PearlPlus-DelayedMessage");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }
    }

    @Override
    public void onDisable() {
        synchronized (stateLock) {
            cancelPendingRequests();
            if (scheduler != null) scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Invalidates late callbacks as well as scheduled messages; never waits for a worker. */
    public void cancelPendingRequests() {
        synchronized (stateLock) {
            cancelActiveRequest();
            scopes.clear();
            refill.clear();
            chat.clear();
        }
    }

    /** Do not mutate loader registrations while one of our requests is running. */
    public boolean registerWhenIdle(BooleanSupplier registration) {
        synchronized (stateLock) {
            if (scheduler == null || !PLUGIN_CONFIG.autoLoad.enabled || requests.isBusy()) return false;
            return registration.getAsBoolean();
        }
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(WhisperChatEvent.class, this::onWhisper),
            of(PublicChatEvent.class, this::onPublicChat),
            of(ClientTickEvent.class, event -> {
                synchronized (stateLock) { refill.tick(); chat.tick(); }
            }),
            of(ClientDisconnectEvent.class, event -> cancelPendingRequests()));
    }

    private void onWhisper(WhisperChatEvent event) {
        if (event.outgoing()) return;
        synchronized (stateLock) {
            if (event.sender() != null && event.sender().getProfileId() != null)
                refill.reply(event.sender().getProfileId(), event.message());
        }
        processLoadCommand(event.sender(), event.message(), false);
    }

    private void onPublicChat(PublicChatEvent event) {
        processLoadCommand(event.sender(), event.message(), true);
    }

    private void processLoadCommand(PlayerListEntry sender, String message, boolean publicChat) {
        var config = PLUGIN_CONFIG.autoLoad;
        if (!config.enabled || sender == null
            || sender.getProfileId() == null || sender.getName() == null) return;
        var input = LoadInput.parse(message, publicChat || config.allowNoiseAfterPearl);
        if (input.isEmpty()) return;
        String name = sender.getName();
        synchronized (stateLock) {
            if (scheduler == null || scheduler.isShutdown() || !config.enabled) return;
            var selected = input.get().select(config.entries(sender.getProfileId()));
            if (selected.isEmpty()) return; // unassigned users cannot produce log or chat spam
            PearlEntry pearl = selected.get();
            var proxy = Proxy.getInstance();
            if (!proxy.isConnected() || proxy.isInQueue()) return;
            var automatic = PLUGIN_CONFIG.autoDetect.get(pearl.target());
            if (!AutoPearlAccess.mayLoad(automatic, sender.getProfileId(), AutoDetectModule.currentScope(),
                config.canAutoRegister(sender.getProfileId(), pearl.alias()))) return;
            if (automatic != null) {
                // A changed/missing trapdoor or edited built-in loader must not redirect an auto registration.
                if (!World.isChunkLoadedBlockPos(automatic.x(), automatic.z())
                    || !World.getBlock(automatic.x(), automatic.y(), automatic.z()).name().endsWith("_trapdoor")
                    || World.getBlock(automatic.x(), automatic.y(), automatic.z()).name().endsWith("iron_trapdoor")
                    || CONFIG.client.extra.pearlLoader.pearls.stream().noneMatch(p -> p.id().equals(pearl.target())
                        && p.x() == automatic.x() && p.y() == automatic.y() && p.z() == automatic.z())) return;
            }
            long token = requests.begin(System.nanoTime(), COOLDOWN_NANOS);
            if (token == 0) return; // one physical loader operation/announcement at a time
            activePlayer = sender.getProfileId(); activeBase = pearl.alias();
            Object session = proxy.getClient();
            config.rememberName(sender.getProfileId(), name);
            try {
                timeout = scheduler.schedule(() -> {
                    synchronized (stateLock) {
                        if (!requests.isCurrent(token)) return;
                        if (live(token, session, publicChat)) {
                            discordAndIngameNotification(Embed.builder().title("Pearl completion timed out")
                                .addField("Player", name).addField("Pearl", pearl.alias()).errorColor());
                        }
                        cancelActiveRequest();
                    }
                }, 120, TimeUnit.SECONDS);
                var source = new CommandSource() {
                    @Override public String name() { return "Pearl+"; }
                    @Override public boolean validateAccountOwner(CommandContext ctx) { return false; }
                    @Override public void logEmbed(CommandContext ctx, Embed embed) {
                        onLoaderCallback(token, session, sender.getProfileId(), name, pearl, publicChat, embed);
                    }
                };
                var context = CommandContext.create("pl load " + pearl.target(), source);
                context.getData().put("PearlPlusSender", sender);
                COMMAND.execute(context);
                var result = context.getEmbed();
                // Zenith exposes no structured completion result here. Fail closed on unknown titles.
                boolean accepted = "Loading Pearl".equals(result.title());
                String response = result.isTitlePresent() ? result.title() : "Pearl load was not accepted";
                if (!accepted) cancelActiveRequest();
                discordAndIngameNotification(result.isTitlePresent() ? result : Embed.builder().title(response).errorColor());
                if (!accepted) whisper(sender.getProfileId(), pearl.alias(), name, response);

            } catch (Exception e) {
                cancelActiveRequest();
                error("Pearl load request failed", e);
            }
        }
    }

    private boolean live(long token, Object session, boolean publicChat) {
        var proxy = Proxy.getInstance();
        return requests.isCurrent(token) && scheduler != null && !scheduler.isShutdown()
            && PLUGIN_CONFIG.autoLoad.enabled
            && proxy.isConnected() && !proxy.isInQueue() && proxy.getClient() == session;
    }

    private void onLoaderCallback(long token, Object session, UUID player, String name, PearlEntry pearl,
                                  boolean publicChat, Embed embed) {
        synchronized (stateLock) {
            // The later "Returned to start pos" callback is deliberately not a second completion.
            if (!"Pearl Loaded!".equals(embed.title()) || !live(token, session, publicChat)
                || !requests.complete(token)) return;
            if (timeout != null) timeout.cancel(false);
            timeout = null;
            discordAndIngameNotification(Embed.builder().title("Pearl interaction completed")
                .addField("Player", name).addField("Pearl", pearl.alias()).successColor());
            try {
                refill.offer(player, name, pearl.alias(), session);
            } catch (Exception e) {
                // Optional refill failures must not leave registration blocked behind a completed load.
                warn("Spare pearl offer failed: {}", e.getMessage());
            }
            int delay = Math.clamp(PLUGIN_CONFIG.autoLoad.publicChatDelaySeconds, 0, 300);
            String template = PLUGIN_CONFIG.autoLoad.publicPullMessage;
            var messageValid = scopes.token(player, pearl.alias());
            announcement = scheduler.schedule(() -> {
                synchronized (stateLock) {
                    try {
                        if (live(token, session, publicChat)) chat.enqueue(null, PullAnnouncement.format(
                            publicChat, template, name, pearl.alias(), LocalTime.now().format(TIME_FORMAT)), () -> {}, messageValid, 2);
                    } catch (Exception e) {
                        error("Pearl completion announcement failed", e);
                    } finally {
                        requests.release(token);
                        activePlayer = null; activeBase = null;
                        announcement = null;
                    }
                }
            }, delay, TimeUnit.SECONDS);
        }
    }

    private void sendPublicMessage(String message) {
        chat.publicMessage(message);
    }
}
