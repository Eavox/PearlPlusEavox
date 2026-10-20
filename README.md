# PearlPlus 1.1.5

Allow selected players to activate registered ZenithProxy pearl loaders through whispers or public chat, without granting account-owner permissions.

## Compatibility and installation

This release targets the current ZenithProxy **1.21.4** API and Java 21 bytecode. Other Minecraft builds are not advertised as supported until tested. Zenith's own runtime requirements still apply.

Download the ready-to-use JAR from [GitHub Releases](https://github.com/Eavox/PearlPlusEavox/releases/latest).

On Linux, run these shell commands **from your ZenithProxy folder**:

```sh
mkdir -p plugins
curl -fL --retry 3 -o plugins/PearlPlus-1.1.5.jar https://github.com/Eavox/PearlPlusEavox/releases/download/1.1.5/PearlPlus-1.1.5.jar
```

Use Zenith's **Java release channel**. Run `plugins on` in the **Zenith console**, then restart Zenith and run `plugins list` to confirm PearlPlus loaded. Remove older PearlPlus JARs before restarting, but keep the config.

Build with `./gradlew build` (Windows: `gradlew.bat build`). Place `build/libs/PearlPlus-1.1.5.jar` in the proxy's plugins directory, replacing the old PearlPlus JAR, and restart Zenith. Do not keep both plugin versions installed.

Configuration remains in `plugins/config/pearl-plus.json`. Existing `allowed` UUID lists and `alias=target` entries are preserved. The new `playerNames` map caches display names; older entries show UUIDs until a name is learned from an authorized request or an allow command. Listing never requires a network lookup.

## Management

Commands require the account owner. `pp` is an alias for `pearl+`.

```text
pp on
pp off
pp allow <playerName> <keyword> [pearlId]
pp deny <playerName-or-UUID> <keyword>
pp list [page]
pp strict <on/off>
pp autodetect <on/off>
pp delay <seconds>
pp message <text>
```

Register each loader first with `pearlLoader add <id> <x> <y> <z>`. `pp allow Alex Spawn AlexBase` grants Alex the keyword `Spawn` for the existing loader ID `AlexBase`. Omitting the ID uses the keyword itself. Keywords are case-insensitive; target IDs retain their exact case and must already exist. Neither may contain whitespace, control characters or `=`; the maximum is 128 characters.

`pp deny <UUID> <keyword>` works even when profile services are unavailable. `pp list` includes UUIDs and shows five players per page. Very long per-player lists are shortened in the display; the stored permissions are unaffected.

## Loading

Both public chat and whispers work whenever PearlPlus is enabled (`pp on`). Whisper examples:

```text
/w <botName> load
/w <botName> load Spawn
```

Without a keyword, the first valid assigned entry is used. An unassigned keyword is always rejected. Strict mode rejects extra arguments; `pp strict off` allows trailing anti-spam text after a valid keyword.

Public chat examples (no prefix):

```text
load Spawn
load Spawn randomText
```

Public commands always allow trailing anti-spam text. The old `publicChatEnabled` setting is ignored, and the `pp publicchat` mode switch has been removed. No prefix is required: type `load Spawn` directly. The old `publicChatAffix` setting is ignored, so existing installations need no config edit. Prefixed commands such as `!load Spawn` are no longer accepted.

## Completion and request limits

- A load rejection never schedules a completion announcement.
- Announcements begin only after Zenith reports its `Pearl Loaded!` callback. This reports the loader interaction, not independent proof that a player teleported.
- `pp delay 7` waits seven seconds after that callback. The supported range is 0-300 seconds; values outside that range in existing JSON are clamped.
- `pp message Pearl interaction completed for {player} at {time}` accepts spaces. Placeholders: `{player}`, `{pearl}`, `{time}` (proxy host local time). Messages are limited to 256 characters after expansion.
- Whisper-triggered completion announces exactly `A Divinity Member have been pulled !` in public chat, without a name or pearl ID. Public-chat requests announce `{player} has been pulled !`.
- `pp message` customizes only the public-request announcement. The old `publicChatSuccessMessage` setting is replaced by `publicPullMessage` so upgrades receive the new default. Whisper announcements stay anonymous.
- Only one PearlPlus request, including its pending public announcement, is active at a time. There is a three-second global request cooldown. Busy/cooldown requests are silently ignored to avoid reply spam.
- Requests expire after 120 seconds without a completion callback and log a timeout. Late callbacks from expired or cancelled requests are ignored.
- Disable, disconnect and permission revocations cancel pending callbacks/announcements immediately. Cancellation does not stop an already-started upstream Baritone operation.
- Completion matching follows the current Zenith callback titles and fails closed if an upstream update changes them.

## Validation

`./gradlew build` compiles, packages and runs regression tests for parsing, permissions, exact target case, request limits and stale-callback invalidation. Live Minecraft/server behavior still needs a smoke test on your proxy: successful load, active-player rejection, missing loader, delayed announcement, and disconnect/disable while waiting.

## Automatic pearl setup (no player command or clicking)

Enabled by default for players on Zenith's **regular whitelist** (`whitelist add PlayerName`). PearlPlus checks visible ender pearls once per second without adding a separate scanning thread.

1. Keep the bot nearby and online while the player throws their pearl into the chamber.
2. The pearl must settle in water or a bubble column beneath a manually usable trapdoor. The trigger must be in the same X/Z block column, at the pearl's block height or up to two blocks higher.
3. With two trapdoors above the pearl, detection selects the **lowest/closest trapdoor**, not the higher one. Solid obstructions and iron trapdoors are rejected.
4. After five seconds of stable observations, the bot saves that trigger in Zenith's pearl-loader config and grants its owner an unused keyword: `pearl1`, `pearl2`, etc. It whispers the assigned keyword privately. No blocks are clicked during registration.
5. The player can say `load pearl1` in public chat or whisper `/w BotName load pearl1`. Bare `load` uses their first valid permission, including any older manual entries.

Ownership comes from the server's projectile owner entity ID, matched to a visible player. Unknown owners are never inferred from the nearest player. An old pearl with unavailable ownership cannot be registered automatically; the bot should witness the owner throwing a new pearl. If different owners or an unidentified pearl share the same detected trigger, registration is skipped.

Detection does not overwrite existing loaders or manual permission aliases. A chamber already registered manually is left alone. Automatic entries are tied to their owner, the configured server address/port and dimension; loading is refused in a different location scope, after removal from the whitelist, or when the stored trigger is missing/changed. This does not prove the pearl is still present or that a teleport succeeded.

`pp autodetect off` disables new automatic registrations while keeping existing loaders. `pp off` disables both detection and loading. `pp deny` revokes a keyword; the remembered automatic registration prevents detection from silently restoring it, including after a restart. An owner can restore access with `pp allow` using the original target ID shown by `pp list`.

To bound work and storage, detection skips scans with more than 256 visible pearls and stores at most 256 automatic chamber registrations. It saves at most one new chamber per second. Automated tests simulate the described layout; the actual chamber still needs an in-game smoke test.
