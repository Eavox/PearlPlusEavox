package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.discord.Embed;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.LoadInput;
import dev.zenith.pearlplus.PearlEntry;
import dev.zenith.pearlplus.RequestTracker;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.COMMAND;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoLoadModule extends Module {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(3);
    private final Object stateLock = new Object();
    private final RequestTracker requests = new RequestTracker();
    private ScheduledThreadPoolExecutor scheduler;
    private ScheduledFuture<?> timeout;
    private ScheduledFuture<?> announcement;

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
            requests.cancel();
            if (timeout != null) timeout.cancel(false);
            if (announcement != null) announcement.cancel(false);
            timeout = null;
            announcement = null;
        }
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(WhisperChatEvent.class, this::onWhisper),
            of(PublicChatEvent.class, this::onPublicChat),
            of(ClientDisconnectEvent.class, event -> cancelPendingRequests()));
    }

    private void onWhisper(WhisperChatEvent event) {
        if (event.outgoing()) return;
        processLoadCommand(event.sender(), event.message(), false);
    }

    private void onPublicChat(PublicChatEvent event) {
        String affix = PLUGIN_CONFIG.autoLoad.publicChatAffix;
        if (affix == null || affix.isBlank() || event.message() == null) return;
        String message = event.message().trim();
        if (!message.regionMatches(true, 0, affix, 0, affix.length())) return;
        processLoadCommand(event.sender(), message.substring(affix.length()).trim(), true);
    }

    private void processLoadCommand(PlayerListEntry sender, String message, boolean publicChat) {
        var config = PLUGIN_CONFIG.autoLoad;
        if (!config.enabled || config.publicChatEnabled != publicChat || sender == null
            || sender.getProfileId() == null || sender.getName() == null) return;
        var input = LoadInput.parse(message, publicChat || config.allowNoiseAfterPearl);
        if (input.isEmpty()) return;
        String name = sender.getName();
        synchronized (stateLock) {
            if (scheduler == null || scheduler.isShutdown() || !config.enabled
                || config.publicChatEnabled != publicChat) return;
            var selected = input.get().select(config.entries(sender.getProfileId()));
            if (selected.isEmpty()) return; // unassigned users cannot produce log or chat spam
            PearlEntry pearl = selected.get();
            var proxy = Proxy.getInstance();
            if (!proxy.isConnected() || proxy.isInQueue()) return;
            long token = requests.begin(System.nanoTime(), COOLDOWN_NANOS);
            if (token == 0) return; // one physical loader operation/announcement at a time
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
                        cancelPendingRequests();
                    }
                }, 120, TimeUnit.SECONDS);
                var source = new CommandSource() {
                    @Override public String name() { return "Pearl+"; }
                    @Override public boolean validateAccountOwner(CommandContext ctx) { return false; }
                    @Override public void logEmbed(CommandContext ctx, Embed embed) {
                        onLoaderCallback(token, session, name, pearl, publicChat, embed);
                    }
                };
                var context = CommandContext.create("pl load " + pearl.target(), source);
                context.getData().put("PearlPlusSender", sender);
                COMMAND.execute(context);
                var result = context.getEmbed();
                // Zenith exposes no structured completion result here. Fail closed on unknown titles.
                boolean accepted = "Loading Pearl".equals(result.title());
                String response = result.isTitlePresent() ? result.title() : "Pearl load was not accepted";
                if (!accepted) cancelPendingRequests();
                discordAndIngameNotification(result.isTitlePresent() ? result : Embed.builder().title(response).errorColor());
                if (publicChat) {
                    sendPublicMessage(name + ": " + (accepted ? "loading your pearl" : response));
                } else {
                    sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, ChatUtil.sanitizeChatMessage(response)));
                }
            } catch (Exception e) {
                cancelPendingRequests();
                error("Pearl load request failed", e);
            }
        }
    }

    private boolean live(long token, Object session, boolean publicChat) {
        var proxy = Proxy.getInstance();
        return requests.isCurrent(token) && scheduler != null && !scheduler.isShutdown()
            && PLUGIN_CONFIG.autoLoad.enabled && PLUGIN_CONFIG.autoLoad.publicChatEnabled == publicChat
            && proxy.isConnected() && !proxy.isInQueue() && proxy.getClient() == session;
    }

    private void onLoaderCallback(long token, Object session, String name, PearlEntry pearl,
                                  boolean publicChat, Embed embed) {
        synchronized (stateLock) {
            // The later "Returned to start pos" callback is deliberately not a second completion.
            if (!"Pearl Loaded!".equals(embed.title()) || !live(token, session, publicChat)
                || !requests.complete(token)) return;
            if (timeout != null) timeout.cancel(false);
            timeout = null;
            discordAndIngameNotification(Embed.builder().title("Pearl interaction completed")
                .addField("Player", name).addField("Pearl", pearl.alias()).successColor());
            if (!publicChat) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Pearl interaction completed: " + pearl.alias()));
                requests.release(token);
                return;
            }
            int delay = Math.clamp(PLUGIN_CONFIG.autoLoad.publicChatDelaySeconds, 0, 300);
            String template = PLUGIN_CONFIG.autoLoad.publicChatSuccessMessage;
            announcement = scheduler.schedule(() -> {
                synchronized (stateLock) {
                    try {
                        if (live(token, session, true)) sendPublicMessage(replacePlaceholders(template, name, pearl.alias()));
                    } catch (Exception e) {
                        error("Pearl completion announcement failed", e);
                    } finally {
                        requests.release(token);
                        announcement = null;
                    }
                }
            }, delay, TimeUnit.SECONDS);
        }
    }

    private void sendPublicMessage(String message) {
        if (message == null || message.isBlank() || !Proxy.getInstance().isConnected()) return;
        String sanitized = ChatUtil.sanitizeChatMessage(message);
        if (sanitized.length() > 256) sanitized = sanitized.substring(0, 256);
        if (!sanitized.isBlank()) sendClientPacketAsync(new ServerboundChatPacket(sanitized));
    }

    private String replacePlaceholders(String template, String playerName, String pearlName) {
        if (template == null) template = "Pearl interaction completed for {player}";
        return template.replace("{player}", playerName).replace("{pearl}", pearlName)
            .replace("{time}", LocalTime.now().format(TIME_FORMAT));
    }
}
