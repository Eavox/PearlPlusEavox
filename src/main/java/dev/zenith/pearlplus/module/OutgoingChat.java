package dev.zenith.pearlplus.module;

import com.zenith.Proxy;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.ChatOutbox;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import static dev.zenith.pearlplus.PearlPlusPlugin.*;

final class OutgoingChat {
    private final ChatOutbox queue = new ChatOutbox();
    private record Completion(ChatOutbox.Message message, Throwable error) {}
    private final java.util.concurrent.ConcurrentLinkedQueue<Completion> completions = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile String status = "No messages submitted yet";
    void clear() { queue.clear(); }
    String status() { return status + "\n" + queue.status() + " (submission is not server delivery confirmation)"; }
    void whisper(String recipient, String text) { enqueue(recipient, text, () -> {}); }
    void publicMessage(String text) { enqueue(null, text, () -> {}); }
    void enqueue(String recipient, String text, Runnable onDispatch) {
        enqueue(recipient, text, onDispatch, () -> true);
    }
    void enqueue(String recipient, String text, Runnable onDispatch, java.util.function.BooleanSupplier valid) {
        enqueue(recipient, text, onDispatch, valid, 1);
    }
    void enqueue(String recipient, String text, Runnable onDispatch, java.util.function.BooleanSupplier valid, int priority) {
        var proxy = Proxy.getInstance();
        if (!PLUGIN_CONFIG.autoLoad.enabled || !proxy.isConnected() || proxy.isInQueue()) return;
        if (!queue.add(proxy.getClient(), recipient, text, onDispatch, System.nanoTime(), valid, priority))
            LOG.warn("Pearl+ outgoing message queue is full");
    }
    void tick() {
        var proxy = Proxy.getInstance();
        if (!PLUGIN_CONFIG.autoLoad.enabled || !proxy.isConnected() || proxy.isInQueue()) return;
        var client = proxy.getClient();
        Completion completion;
        while ((completion = completions.poll()) != null) {
            var sent = completion.message();
            if (sent.session() != client || !sent.valid().getAsBoolean()) continue;
            if (completion.error() != null) {
                status = "Transport failed: " + completion.error().getMessage();
                LOG.warn("[Pearl+ chat] {}", status);
            } else {
                sent.onDispatch().run();
                status = (sent.recipient() == null ? "Public" : "Whisper to " + sent.recipient())
                    + " written to connection: " + sent.text();
                LOG.info("[Pearl+ chat] {}", status);
            }
        }
        var message = queue.poll(client, System.nanoTime());
        if (message == null || !message.valid().getAsBoolean()) return;
        try {
            String text = ChatUtil.sanitizeChatMessage(message.text());
            var packet = message.recipient() == null ? new ServerboundChatPacket(text)
                : ChatUtil.getWhisperChatPacket(message.recipient(), text);
            // ChatUtil includes the /w command and player name in the protocol length limit.
            if (packet.getMessage().length() > 256)
                packet = new ServerboundChatPacket(packet.getMessage().substring(0, 256));
            org.geysermc.mcprotocollib.network.packet.Packet outgoing = packet;
            if (packet.getMessage().startsWith("/")) {
                String command = packet.getMessage().substring(1);
                outgoing = com.zenith.Globals.CONFIG.client.chatSigning.signCommands
                    && com.zenith.Globals.CACHE.getChatCache().isSignableCommand(command)
                    ? new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket(command)
                    : new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket(command);
            }
            var sendPacket = outgoing;
            client.executeInEventLoop(() -> {
                if (!message.valid().getAsBoolean() || Proxy.getInstance().getClient() != client) return;
                try {
                    var future = client.send(sendPacket);
                    if (future == null) completions.add(new Completion(message, new IllegalStateException("packet filtered before write")));
                    else future.addListener(done -> completions.add(new Completion(message,
                        done.isSuccess() ? null : new IllegalStateException("packet write failed", done.cause()))));
                } catch (Exception e) { completions.add(new Completion(message, e)); }
            });
            status = "Awaiting transport write: " + text;
        } catch (Exception e) {
            status = "Send failed: " + e.getMessage();
            LOG.error("Pearl+ outgoing message failed", e);
        }
    }
}
