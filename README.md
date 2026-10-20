# PearlPlus 1.1.4

Allow selected players to activate registered ZenithProxy pearl loaders through whispers or public chat, without granting account-owner permissions.

## Compatibility and installation

This release targets the current ZenithProxy **1.21.4** API and Java 21 bytecode. Other Minecraft builds are not advertised as supported until tested. Zenith's own runtime requirements still apply.

Download the ready-to-use JAR from [GitHub Releases](https://github.com/Eavox/PearlPlusEavox/releases/latest).

On Linux, run these shell commands **from your ZenithProxy folder**:

```sh
mkdir -p plugins
curl -fL --retry 3 -o plugins/PearlPlus-1.1.4.jar https://github.com/Eavox/PearlPlusEavox/releases/download/1.1.4/PearlPlus-1.1.4.jar
```

Use Zenith's **Java release channel**. Run `plugins on` in the **Zenith console**, then restart Zenith and run `plugins list` to confirm PearlPlus loaded. Remove older PearlPlus JARs before restarting, but keep the config.

Build with `./gradlew build` (Windows: `gradlew.bat build`). Place `build/libs/PearlPlus-1.1.4.jar` in the proxy's plugins directory, replacing the old PearlPlus JAR, and restart Zenith. Do not keep both plugin versions installed.

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
pp publicchat <on/off>
pp delay <seconds>
pp message <text>
```

Register each loader first with `pearlLoader add <id> <x> <y> <z>`. `pp allow Alex Spawn AlexBase` grants Alex the keyword `Spawn` for the existing loader ID `AlexBase`. Omitting the ID uses the keyword itself. Keywords are case-insensitive; target IDs retain their exact case and must already exist. Neither may contain whitespace, control characters or `=`; the maximum is 128 characters.

`pp deny <UUID> <keyword>` works even when profile services are unavailable. `pp list` includes UUIDs and shows five players per page. Very long per-player lists are shortened in the display; the stored permissions are unaffected.

## Loading

Whisper mode is the default:

```text
/w <botName> load
/w <botName> load Spawn
```

Without a keyword, the first valid assigned entry is used. An unassigned keyword is always rejected. Strict mode rejects extra arguments; `pp strict off` allows trailing anti-spam text after a valid keyword.

Public chat mode is enabled with `pp publicchat on`:

```text
load Spawn
load Spawn randomText
```

**Public mode disables whisper loading.** Public commands always allow trailing anti-spam text. No prefix is required: type `load Spawn` directly. The old `publicChatAffix` setting is ignored, so existing installations need no config edit. Prefixed commands such as `!load Spawn` are no longer accepted.

## Completion and request limits

- A load rejection never schedules a completion announcement.
- Announcements begin only after Zenith reports its `Pearl Loaded!` callback. This reports the loader interaction, not independent proof that a player teleported.
- `pp delay 7` waits seven seconds after that callback. The supported range is 0-300 seconds; values outside that range in existing JSON are clamped.
- `pp message Pearl interaction completed for {player} at {time}` accepts spaces. Placeholders: `{player}`, `{pearl}`, `{time}` (proxy host local time). Messages are limited to 256 characters after expansion.
- Existing custom message templates are preserved. New configs use `Pearl interaction completed for {player}`.
- Only one PearlPlus request, including its pending public announcement, is active at a time. There is a three-second global request cooldown. Busy/cooldown requests are silently ignored to avoid reply spam.
- Requests expire after 120 seconds without a completion callback and log a timeout. Late callbacks from expired or cancelled requests are ignored.
- Disable, disconnect, public-mode changes and permission revocations cancel pending callbacks/announcements immediately. Cancellation does not stop an already-started upstream Baritone operation.
- Completion matching follows the current Zenith callback titles and fails closed if an upstream update changes them.

## Validation

`./gradlew build` compiles, packages and runs regression tests for parsing, permissions, exact target case, request limits and stale-callback invalidation. Live Minecraft/server behavior still needs a smoke test on your proxy: successful load, active-player rejection, missing loader, delayed announcement, and disconnect/disable while waiting.

Auto-detection remains an unimplemented placeholder; this release does not enable it.
