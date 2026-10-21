package dev.zenith.pearlplus.module;

import com.zenith.Proxy;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.ChatUtil;
import com.zenith.util.RequestFuture;
import dev.zenith.pearlplus.RefillOffers;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.zenith.Globals.*;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

/** Inventory actions are checked again at execution, never against a stale slot. */
final class PearlRefill {
    private final OutgoingChat chat;
    private final dev.zenith.pearlplus.InteractionScope scopes;
    PearlRefill(OutgoingChat chat, dev.zenith.pearlplus.InteractionScope scopes) { this.chat = chat; this.scopes = scopes; }
    void cancel(UUID player, String base) {
        offers.cancel(player, base);
        deliveries.removeIf(d -> {
            if (d.player().equals(player) && d.base().equalsIgnoreCase(base)) { d.valid().set(false); return true; }
            return false;
        });
    }
    private final RefillOffers offers = new RefillOffers();
    private final AtomicLong generation = new AtomicLong();
    private final ConcurrentLinkedQueue<Delivery> deliveries = new ConcurrentLinkedQueue<>();
    private record Delivery(RequestFuture future, AtomicBoolean packetCreated, AtomicBoolean valid, dev.zenith.pearlplus.DropReceipt receipt,
                            UUID player, String base, java.util.function.BooleanSupplier scoped, String name, Object session, long generation, long started) {}

    void clear() {
        generation.incrementAndGet();
        offers.clear();
        deliveries.forEach(d -> d.valid().set(false));
        deliveries.clear();
    }

    void offer(UUID player, String name, String alias, Object session) {
        if (!live(session)) return;
        if (stock() == 0) { ask(player, alias, name, false); return; }
        long stamp = generation.get();
        var scoped = scopes.token(player, alias);
        chat.enqueue(name, "Do you need a pearl to reset your pearl? Whisper yes or no within 60 seconds.",
            () -> { if (live(session)) offers.add(player, name, alias, session, System.nanoTime()); },
            () -> stamp == generation.get() && live(session) && scoped.getAsBoolean());
    }

    void reply(UUID player, String message) {
        Object session = Proxy.getInstance().getClient();
        var reply = offers.reply(player, message, session, System.nanoTime());
        if (reply == null || !live(session)
            || !PLUGIN_CONFIG.autoLoad.canAutoRegister(player, reply.offer().alias())) return;
        String name = reply.offer().name();
        String base = reply.offer().alias();
        var scoped = scopes.token(player, base);
        if (!reply.yes()) {
            whisper(player, base, name, "stay safe !");
            ask(player, base, name, true);
            return;
        }
        if (stock() == 0) { ask(player, base, name, false); return; }
        if (deliveries.size() >= 64) { whisper(player, base, name, "I cannot drop a pearl right now. stay safe !"); return; }
        long stamp = generation.get();
        var created = new AtomicBoolean();
        var valid = new AtomicBoolean(true);
        var receipt = new dev.zenith.pearlplus.DropReceipt();
        var action = new InventoryAction() {
            @Override public int containerId() { return 0; }
            @Override public MinecraftPacket packet() {
                if (!valid.get() || !scoped.getAsBoolean() || stamp != generation.get() || !live(session)
                    || Proxy.getInstance().getCurrentPlayer().get() != null
                    || !PLUGIN_CONFIG.autoLoad.canAutoRegister(player, reply.offer().alias())) return null;
                int slot = pearlSlot();
                if (slot < 0) return null;
                receipt.expect(slot, CACHE.getPlayerCache().getPlayerInventory().get(slot).getAmount());
                var packet = new DropItem(slot, DropItemAction.DROP_FROM_SELECTED).packet();
                created.set(packet != null);
                return packet;
            }
        };
        var future = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .actions(action).priority(3000).build());
        deliveries.add(new Delivery(future, created, valid, receipt, player, base, scoped, name, session, stamp, System.nanoTime()));
    }

    void tick() {
        for (var delivery : deliveries) {
            boolean expired = System.nanoTime() - delivery.started() >= TimeUnit.SECONDS.toNanos(10);
            if (!delivery.future().isDone() && !expired) continue;
            if (!expired && delivery.future().getNow() && delivery.packetCreated().get()
                && !delivery.receipt().decreased() && System.nanoTime()-delivery.started() < TimeUnit.SECONDS.toNanos(3)) continue;
            delivery.valid().set(false);
            if (!deliveries.remove(delivery)) continue;
            if (!delivery.scoped().getAsBoolean() || delivery.generation() != generation.get() || !live(delivery.session())) continue;
            // Zenith also completes skipped inventory actions: require a real drop packet.
            boolean dropped = delivery.future().getNow() && delivery.packetCreated().get();
            whisper(delivery.player(), delivery.base(), delivery.name(), dropped ? (delivery.receipt().decreased() ? "1 pearl left my inventory. Check the ground. stay safe !" : "Sent the drop for 1 pearl. Please check the ground. stay safe !") : "I could not drop a pearl right now. stay safe !");
            ask(delivery.player(), delivery.base(), delivery.name(), false);
        }
    }

    void serverSlot(int container, int slot, ItemStack item, Object session) {
        if (container != 0) return;
        int count = isPearl(item) ? item.getAmount() : 0;
        for (var d:deliveries) if(d.session()==session && d.packetCreated().get()) d.receipt().update(slot,count);
    }

    int stock() {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().isInQueue()) return -1;
        var items = CACHE.getPlayerCache().getPlayerInventory();
        int total = 0;
        for (int slot = 9; slot <= 45 && slot < items.size(); slot++) {
            var item = items.get(slot);
            if (isPearl(item)) total += item.getAmount();
        }
        return total;
    }

    private void ask(UUID player, String base, String name, boolean declined) {
        var config = PLUGIN_CONFIG.refill;
        if (!config.enabled || config.hasAsked(player)
            || !dev.zenith.pearlplus.PearlPlusConfig.RefillConfig.shouldAsk(stock(), declined)) return;
        long stamp = generation.get();
        Object session = Proxy.getInstance().getClient();
        var scoped = scopes.token(player, base);
        chat.enqueue(name, "Im low on pearl can you drop me some.", () -> {
            config.markAsked(player);
            saveConfigAsync();
        }, () -> scoped.getAsBoolean() && stamp == generation.get() && live(session) && !config.hasAsked(player)
            && dev.zenith.pearlplus.PearlPlusConfig.RefillConfig.shouldAsk(stock(), declined), 0);
    }

    private boolean live(Object session) {
        var proxy = Proxy.getInstance();
        return PLUGIN_CONFIG.autoLoad.enabled && PLUGIN_CONFIG.refill.enabled && proxy.isConnected() && !proxy.isInQueue()
            && proxy.getClient() == session;
    }
    private int pearlSlot() {
        return InventoryUtil.searchPlayerInventory(PearlRefill::isPearl);
    }
    private static boolean isPearl(ItemStack item) {
        if (item == null || item.getAmount() < 1) return false;
        return item.getId() == ItemRegistry.ENDER_PEARL.id();
    }
    private void whisper(UUID player, String base, String name, String message) {
        long stamp = generation.get();
        Object session = Proxy.getInstance().getClient();
        var scoped = scopes.token(player, base);
        chat.enqueue(name, message, () -> {}, () -> scoped.getAsBoolean() && stamp == generation.get() && live(session), 2);
    }
}
