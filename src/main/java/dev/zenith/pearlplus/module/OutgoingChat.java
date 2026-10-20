package dev.zenith.pearlplus.module;

import com.zenith.Proxy;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.ChatOutbox;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import static dev.zenith.pearlplus.PearlPlusPlugin.*;

final class OutgoingChat {
    private final ChatOutbox queue = new ChatOutbox();
    private volatile String status = "No messages submitted yet";
    void clear() { queue.clear(); }
    String status() { return status; }
    void whisper(String recipient, String text) { enqueue(recipient, text, () -> {}); }
    void publicMessage(String text) { enqueue(null, text, () -> {}); }
    void enqueue(String recipient, String text, Runnable onDispatch) {
        var proxy = Proxy.getInstance();
        if (!PLUGIN_CONFIG.autoLoad.enabled || !proxy.isConnected() || proxy.isInQueue()) return;
        if (!queue.add(proxy.getClient(), recipient, text, onDispatch, System.nanoTime()))
            LOG.warn("Pearl+ outgoing message queue is full");
    }
    void tick() {
        var proxy = Proxy.getInstance();
        if (!PLUGIN_CONFIG.autoLoad.enabled || !proxy.isConnected() || proxy.isInQueue()) return;
        var client = proxy.getClient();
        var message = queue.poll(client, System.nanoTime());
        if (message == null) return;
        try {
            String text = ChatUtil.sanitizeChatMessage(message.text());
            var packet = message.recipient() == null ? new ServerboundChatPacket(text)
                : ChatUtil.getWhisperChatPacket(message.recipient(), text);
            // ChatUtil includes the /w command and player name in the protocol length limit.
            if (packet.getMessage().length() > 256)
                packet = new ServerboundChatPacket(packet.getMessage().substring(0, 256));
            client.sendAsync(packet);
            message.onDispatch().run();
            status = (message.recipient() == null ? "Public" : "Whisper to " + message.recipient())
                + " submitted: " + text;
            LOG.info("[Pearl+ chat] {}", status);
        } catch (Exception e) {
            status = "Send failed: " + e.getMessage();
            LOG.error("Pearl+ outgoing message failed", e);
        }
    }
}
