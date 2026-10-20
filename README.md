# PearlPlus 1.1.8

Allow selected players to activate registered ZenithProxy pearl loaders through whispers or public chat, without granting account-owner permissions.

## Compatibility and installation

This release targets the current ZenithProxy **1.21.4** API and Java 21 bytecode. Other Minecraft builds are not advertised as supported until tested. Zenith's own runtime requirements still apply.

Download the ready-to-use JAR from [GitHub Releases](https://github.com/Eavox/PearlPlusEavox/releases/latest).

On Linux, run these shell commands **from your ZenithProxy folder**:

```sh
mkdir -p plugins
curl -fL --retry 3 -o plugins/PearlPlus-1.1.8.jar https://github.com/Eavox/PearlPlusEavox/releases/download/1.1.8/PearlPlus-1.1.8.jar
```

Use Zenith's **Java release channel**. Run `plugins on` in the **Zenith console**, then restart Zenith and run `plugins list` to confirm PearlPlus loaded. Remove older PearlPlus JARs before restarting, but keep the config.

Build with `./gradlew build` (Windows: `gradlew.bat build`). Place `build/libs/PearlPlus-1.1.8.jar` in the proxy's plugins directory, replacing the old PearlPlus JAR, and restart Zenith. Do not keep both plugin versions installed.

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
pp base [name]
pp autodetect <on/off>
pp delay <seconds>
pp message <text>
```

For automatic setup, `pp allow Eavox crewbase` authorizes Eavox at that base **before a pearl or loader exists**. `pp list` shows `crewbase (waiting for pearl)` until registration. This does not allow Eavox to log into or control the Zenith bot.

For a manual assignment, register the loader with `pearlLoader add <id> <x> <y> <z>`, then use the explicit third argument: `pp allow Alex Spawn AlexBase`. Omitting the target ID now grants automatic base access instead of assuming a loader ID. Keywords are case-insensitive; target IDs retain their exact case and must already exist. Neither may contain whitespace, control characters or `=`; the maximum is 128 characters.

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

## Spare pearl after a pull

If the bot has ender pearls when Zenith reports a successful pull, it privately asks that player whether they need one to reset their pearl. Reply `/w <botName> yes` within 60 seconds to drop **one pearl** on the ground at the bot, or `/w <botName> no` to decline. Both finish with `stay safe !`. Public yes/no messages are ignored. Stand near the bot to collect the dropped pearl; it is not placed directly in your inventory.

Offers are single-use and tied to the requesting player. Disable, disconnect or permission revocation cancels them. If inventory is busy, a container is open, someone is controlling the bot, or no pearl remains, it sends a private failure message instead of claiming a drop. Drops use Zenith's inventory manager and recheck the item at execution.

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

The owner names the bot's current base once and grants access through **PearlPlus's own allow list**. Zenith's login/control whitelist is separate and is never used or modified for this access. For example, in the Zenith console:

```text
pp base Crewbase
pp allow Eavox crewbase
pp on
```

The bot now uses the keyword `crewbase` for each player's own automatically detected pearl. Eavox and another player allowed at that base can both say `load crewbase`; each activates only their assigned chamber. Names are case-insensitive and must be single words (for example `Crewbase` or `Crew_Base`). `pp base` shows the current name. No random/generated player-facing keywords are assigned.

Automatic detection is enabled by default, but waits until a base name has been configured. There is one current base name per bot instance; the bot does not infer base names or geographic regions. Existing manual permissions keep their names. PearlPlus checks visible ender pearls once per second without adding a separate scanning thread.

1. Keep the bot nearby and online while the player throws their pearl into the chamber.
2. The pearl must settle in water or a bubble column beneath a manually usable trapdoor. The trigger must be in the same X/Z block column, at the pearl's block height or up to two blocks higher.
3. With two trapdoors above the pearl, detection selects the **lowest/closest trapdoor**, not the higher one. Solid obstructions and iron trapdoors are rejected.
4. After five seconds of stable observations, the bot saves that trigger in Zenith's pearl-loader config and assigns its owner the configured base keyword, such as `crewbase`. It privately confirms registration. No blocks are clicked during registration.
5. The player can say `load crewbase` in public chat or whisper `/w BotName load crewbase`. Bare `load` still uses their first valid permission, including any older manual entries.

Ownership comes from the server's projectile owner entity ID, matched to a visible player. Unknown owners are never inferred from the nearest player. An old pearl with unavailable ownership cannot be registered automatically; the bot should witness the owner throwing a new pearl. If different owners or an unidentified pearl share the same detected trigger, registration is skipped.

Detection does not overwrite existing loaders or manual permission aliases. A single newly detected chamber can replace that player's previous automatic target for the same base. If multiple new eligible chambers are detected for the same player together, detection skips them instead of choosing randomly. A chamber already registered manually is left alone. Automatic entries are tied to their owner, the configured server address/port and dimension; loading is refused in a different location scope, after removal from the PearlPlus allow list, or when the stored trigger is missing/changed. This does not prove the pearl is still present or that a teleport succeeded.

`pp autodetect off` disables new automatic registrations while keeping existing loaders. `pp off` disables both detection and loading. `pp deny` revokes a keyword; the remembered automatic registration prevents detection from silently restoring it, including after a restart. An owner can restore automatic access with `pp allow PlayerName crewbase`. The explicit third target-ID argument remains available for manual assignment. Revoking a base keyword also blocks new chambers from automatically restoring that base permission.

To bound work and storage, detection skips scans with more than 256 visible pearls and stores at most 256 automatic chamber registrations. It saves at most one new chamber per second. Automated tests simulate the described layout; the actual chamber still needs an in-game smoke test.

### Upgrading from 1.1.5

Run `pp base Crewbase` after upgrading. Active automatically generated permissions such as `pearl1` are renamed to the base keyword when their chamber is observed again. A revoked permission is not restored. If multiple eligible legacy chambers for one player are visible, choose the intended target explicitly with `pp allow PlayerName crewbase <targetId>` from `pp list`.

### Access changes in 1.1.8

Automatic setup and loading use PearlPlus permissions only. Players do not need to be on Zenith's whitelist, and being on Zenith's whitelist alone grants no pearl access. Existing saved PearlPlus permissions remain valid. `pp deny PlayerName crewbase` (or UUID) removes pending base access and the assigned keyword together. To use manual two-argument grants from older versions, now supply the target explicitly: `pp allow PlayerName keyword targetId`.
