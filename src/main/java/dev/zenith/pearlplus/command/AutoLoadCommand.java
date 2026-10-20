package dev.zenith.pearlplus.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.feature.api.ProfileData;
import com.zenith.feature.whitelist.PlayerListsManager;
import dev.zenith.pearlplus.PearlEntry;
import dev.zenith.pearlplus.AutoPearlNaming;
import dev.zenith.pearlplus.module.AutoLoadModule;
import dev.zenith.pearlplus.module.AutoDetectModule;

import java.util.UUID;
import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoLoadCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder().name("pearl+").category(CommandCategory.MODULE)
            .description("Allow players to load assigned pearls through whispers or public chat.")
            .usageLines("<on/off>", "allow <playerName> <keyword> [pearlId]",
                "deny <playerName/UUID> <keyword>", "list [page]", "strict <on/off>",
                "status", "base [name]", "autodetect <on/off>", "delay <0-300 seconds>", "message <text>")
            .aliases("pp").build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("pearl+").requires(Command::validateAccountOwner)
            .then(argument("toggle", toggle()).executes(c -> {
                boolean enabled = getToggle(c, "toggle");
                PLUGIN_CONFIG.autoLoad.enabled = enabled;
                MODULE.get(AutoLoadModule.class).syncEnabledFromConfig();
                MODULE.get(AutoDetectModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed().title("Pearl+ " + toggleStrCaps(enabled));
                return OK;
            }))
            .then(literal("status").executes(c -> {
                c.getSource().getEmbed().title("Pearl+ detection status")
                    .description(MODULE.get(AutoDetectModule.class).status() + "\n" + MODULE.get(AutoLoadModule.class).chatStatus());
                return OK;
            }))
            .then(literal("allow").then(argument("playerName", wordWithChars())
                .then(argument("pearlName", wordWithChars())
                    .executes(c -> { return allowPlayer(c, getString(c, "pearlName"), null); })
                    .then(argument("pearlId", wordWithChars())
                        .executes(c -> { return allowPlayer(c, getString(c, "pearlName"), getString(c, "pearlId")); })))))
            .then(literal("deny").then(argument("playerName", wordWithChars())
                .then(argument("pearlName", wordWithChars()).executes(c -> {
                    String player = getString(c, "playerName");
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(player);
                    } catch (IllegalArgumentException e) {
                        var profile = PlayerListsManager.getProfileFromUsername(player);
                        if (profile.isEmpty()) {
                            c.getSource().getEmbed().title("Profile lookup failed")
                                .description("Retry, or use the player's UUID from pp list to revoke access offline.");
                            return ERROR;
                        }
                        uuid = profile.get().uuid();
                    }
                    String alias = getString(c, "pearlName");
                    boolean removed = PLUGIN_CONFIG.autoLoad.deny(uuid, alias);
                    if (removed) MODULE.get(AutoLoadModule.class).cancelPendingRequests();
                    c.getSource().getEmbed().title(removed ? "Removed pearl " + alias + " from " + player
                        : "No matching permission for " + player + ": " + alias);
                    return removed ? OK : ERROR;
                }))))
            .then(literal("list").executes(c -> { return list(c.getSource(), 1); })
                .then(argument("page", integer(1)).executes(c -> { return list(c.getSource(), getInteger(c, "page")); })))
            .then(literal("strict").then(argument("toggle", toggle()).executes(c -> {
                boolean strict = getToggle(c, "toggle");
                PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl = !strict;
                c.getSource().getEmbed().title("Pearl+ strict " + toggleStrCaps(strict));
                return OK;
            })))
            .then(literal("base").executes(c -> {
                String base = AutoPearlNaming.baseAlias(PLUGIN_CONFIG.autoDetect.baseName);
                c.getSource().getEmbed().title(base == null ? "Base not set" : "Base: " + base)
                    .description("Set this bot's base with pp base Crewbase. Players in pp allow then use load crewbase.");
                return OK;
            }).then(argument("name", wordWithChars()).executes(c -> {
                String base = AutoPearlNaming.baseAlias(getString(c, "name"));
                if (base == null) {
                    c.getSource().getEmbed().title("Invalid base name")
                        .description("Use 1-128 characters without whitespace, control characters or '='.");
                    return ERROR;
                }
                MODULE.get(AutoLoadModule.class).cancelPendingRequests();
                MODULE.get(AutoDetectModule.class).setBase(base);
                c.getSource().getEmbed().title("Pearl+ base set to " + base)
                    .description("Players in pp allow can throw a pearl nearby. Once detected, use load " + base
                        + " in public chat or whispers.");
                return OK;
            })))
            .then(literal("autodetect").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.autoDetect.enabled = getToggle(c, "toggle");
                MODULE.get(AutoDetectModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed().title("Pearl+ auto-detection " + toggleStrCaps(PLUGIN_CONFIG.autoDetect.enabled));
                return OK;
            })))
            .then(literal("delay").then(argument("seconds", integer(0, 300)).executes(c -> {
                int delay = getInteger(c, "seconds");
                PLUGIN_CONFIG.autoLoad.publicChatDelaySeconds = delay;
                c.getSource().getEmbed().title("Pearl+ completion announcement delay: " + delay + " seconds");
                return OK;
            })))
            .then(literal("message").then(argument("text", greedyString()).executes(c -> {
                String text = getString(c, "text");
                if (text.isBlank() || text.length() > 256 || text.chars().anyMatch(Character::isISOControl)) {
                    c.getSource().getEmbed().title("Message must contain 1-256 characters without control characters");
                    return ERROR;
                }
                PLUGIN_CONFIG.autoLoad.publicPullMessage = text;
                c.getSource().getEmbed().title("Pearl+ public-request announcement updated")
                    .description(text + "\nPlaceholders: {player}, {pearl}, {time}");
                return OK;
            })));
    }

    private int allowPlayer(com.mojang.brigadier.context.CommandContext<CommandContext> ctx, String alias, String target) {
        if (!PearlEntry.validToken(alias) || (target != null && !PearlEntry.validToken(target))) {
            ctx.getSource().getEmbed().title("Invalid keyword or pearl ID")
                .description("Use 1-128 characters, without whitespace, control characters, or '='.");
            return ERROR;
        }
        if (target != null && CONFIG.client.extra.pearlLoader.pearls.stream().noneMatch(p -> p.id().equals(target))) {
            ctx.getSource().getEmbed().title("Pearl ID not found: " + target)
                .description("Register it with pearlLoader add first. IDs are case-sensitive.");
            return ERROR;
        }
        String name = getString(ctx, "playerName");
        var profile = PlayerListsManager.getProfileFromUsername(name);
        if (profile.isEmpty()) {
            ctx.getSource().getEmbed().title("Profile lookup failed: " + name);
            return ERROR;
        }
        ProfileData player = profile.get();
        if (target == null) {
            String base = AutoPearlNaming.baseAlias(alias);
            PLUGIN_CONFIG.autoLoad.allowBase(player.uuid(), player.name(), base);
            ctx.getSource().getEmbed().title("Allowed " + player.name() + " at " + base)
                .description("PearlPlus access only. Automatic setup will assign their detected chamber; Zenith login access is unchanged.");
            return OK;
        }
        var pearl = new PearlEntry(alias, target);
        PLUGIN_CONFIG.autoLoad.allow(player.uuid(), player.name(), pearl);
        ctx.getSource().getEmbed().title("Allowed " + player.name() + " -> " + pearl.serialize());
        return OK;
    }

    private int list(CommandContext ctx, int page) {
        var snapshot = PLUGIN_CONFIG.autoLoad.snapshot();
        var access = PLUGIN_CONFIG.autoLoad.accessSnapshot();
        access.keySet().forEach(id -> snapshot.putIfAbsent(id, java.util.List.of()));
        var entries = snapshot.entrySet().stream().toList();
        int pages = Math.max(1, (entries.size() + 4) / 5);
        if (page > pages) {
            ctx.getEmbed().title("Page must be between 1 and " + pages);
            return ERROR;
        }
        var embed = ctx.getEmbed().title("Allowed Entries (" + entries.size() + ") - Page " + page + "/" + pages);
        if (entries.isEmpty()) embed.description("None");
        for (var entry : entries.subList((page - 1) * 5, Math.min(page * 5, entries.size()))) {
            String pearls = entry.getValue().stream().flatMap(v -> PearlEntry.parse(v).stream())
                .map(PearlEntry::serialize).collect(Collectors.joining(", "));
            String pending = access.getOrDefault(entry.getKey(), java.util.List.of()).stream()
                .filter(base -> entry.getValue().stream().flatMap(v -> PearlEntry.parse(v).stream())
                    .noneMatch(p -> p.alias().equalsIgnoreCase(base)))
                .map(base -> base + " (waiting for pearl)").collect(Collectors.joining(", "));
            if (!pending.isEmpty()) pearls = pearls.isEmpty() ? pending : pearls + ", " + pending;
            String value = entry.getKey() + "\n" + (pearls.isEmpty() ? "No valid entries" : pearls);
            if (value.length() > 1000) value = value.substring(0, 997) + "...";
            embed.addField(PLUGIN_CONFIG.autoLoad.displayName(entry.getKey()), value);
        }
        return OK;
    }
}
